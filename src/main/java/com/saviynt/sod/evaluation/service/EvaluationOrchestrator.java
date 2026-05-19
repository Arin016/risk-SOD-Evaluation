package com.saviynt.sod.evaluation.service;

import com.saviynt.sod.evaluation.dao.AccessDataDao;
import com.saviynt.sod.evaluation.dao.SodConfigDao;
import com.saviynt.sod.evaluation.dto.EvaluationRequest;
import com.saviynt.sod.evaluation.dto.EvaluationResult;
import com.saviynt.sod.evaluation.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Orchestrates the full SOD evaluation pipeline:
 *
 * Phase 0: Load config (risks, functions, conditions)
 * Phase 1: Load graph + resolve user access (BFS)
 * Phase 2: Evaluate functions → BitSets
 * Phase 3: Detect violations → BitSet AND
 * Phase 4: Evidence collection + persistence (only for violators)
 * Phase 5: Close stale violations
 */
@Service
public class EvaluationOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(EvaluationOrchestrator.class);

    private final AccessGraphService accessGraph;
    private final FunctionEvaluationService functionEval;
    private final ViolationDetectionService violationDetection;
    private final ValidationService validationService;
    private final AccessDataDao accessDataDao;
    private final SodConfigDao configDao;

    @Value("${sod.evaluation.max-depth:14}")
    private int maxDepth;

    public EvaluationOrchestrator(AccessGraphService accessGraph,
                                  FunctionEvaluationService functionEval,
                                  ViolationDetectionService violationDetection,
                                  ValidationService validationService,
                                  AccessDataDao accessDataDao,
                                  SodConfigDao configDao) {
        this.accessGraph = accessGraph;
        this.functionEval = functionEval;
        this.violationDetection = violationDetection;
        this.validationService = validationService;
        this.accessDataDao = accessDataDao;
        this.configDao = configDao;
    }

    public EvaluationResult evaluate(EvaluationRequest request) {
        Instant start = Instant.now();
        long jobId = System.currentTimeMillis();  // TODO: proper job ID from ecmimportjob table

        try {
            log.info("=== SOD Evaluation Started (jobId={}) ===", jobId);
            logMemory("START");

            // Fail fast if securitySystemId not provided
            if (request.securitySystemId() == null) {
                throw new IllegalArgumentException("securitySystemId is required — evaluation must be scoped to a single security system");
            }

            // ─── Phase 0: Load Configuration ───────────────────────────────────
            log.info("Phase 0: Loading configuration...");
            List<Long> rulesetKeys = request.rulesetKeys().isEmpty()
                    ? configDao.loadDefaultRulesetKeys()
                    : request.rulesetKeys();

            List<Risk> risks = configDao.loadActiveRisks(rulesetKeys);
            Set<Long> allFunctionKeys = extractUniqueFunctionKeys(risks);

            Map<Long, SodFunction> functions = configDao.loadFunctions(allFunctionKeys);
            Set<Long> nonSAPFuncKeys = filterByType(functions, SodFunction.FunctionType.NONSAP);
            Set<Long> sapFuncKeys = filterByType(functions, SodFunction.FunctionType.SAP, SodFunction.FunctionType.SAPGROUP);

            Map<Long, NonSAPCondition> nonSAPConditions = configDao.loadNonSAPConditions(nonSAPFuncKeys);
            Map<Long, SAPFunctionDef> sapFunctionDefs = configDao.loadSAPFunctionDefs(sapFuncKeys);
            Set<Long> starTcodeKeys = configDao.loadStarTcodeKeys(request.securitySystemId());
            boolean considerPrecedingZeros = configDao.loadConsiderPrecedingZeros();
            functionEval.setConsiderPrecedingZeros(considerPrecedingZeros);
            Set<String> excludedEntPairs = configDao.loadExcludedEntPairs();

            log.info("Config loaded: {} rulesets, {} risks, {} functions ({} NonSAP, {} SAP), {} star tcodes, precedingZeros={}, excludedEntPairs={}",
                    rulesetKeys.size(), risks.size(), functions.size(), nonSAPFuncKeys.size(), sapFuncKeys.size(), starTcodeKeys.size(), considerPrecedingZeros, excludedEntPairs.size());

            // ─── Phase 1: Load Graph + Resolve Access ──────────────────────────
            log.info("Phase 1: Loading hierarchy graph and resolving user access...");

            // Collect all leaf nodes referenced by functions (tcodes from SAP + entitlements from NonSAP)
            Set<Long> functionLeafNodes = new HashSet<>();
            for (SAPFunctionDef def : sapFunctionDefs.values()) {
                for (var groups : def.conditionsByEndpoint().values()) {
                    for (var group : groups) {
                        for (var cond : group.conditions()) {
                            functionLeafNodes.add(cond.tcodeKey());
                        }
                    }
                }
            }
            for (var entry : nonSAPConditions.entrySet()) {
                collectLeafNodes(entry.getValue(), functionLeafNodes);
            }
            log.info("Phase 1: {} function leaf nodes collected (for subgraph)", functionLeafNodes.size());

            accessGraph.loadGraph(request.securitySystemId(), functionLeafNodes, excludedEntPairs);

            String accountFilter = buildAccountFilter(request);
            Map<Long, List<Long>> directAssignments = accessDataDao.loadAccountEntitlements(accountFilter, request.entitlementQuery());
            Map<Long, long[]> accountMetadata = accessDataDao.loadAccountMetadata(accountFilter);

            // Extract all (objectKey, fieldKey) pairs referenced by functions — only load auth for these
            Set<Long> relevantObjFieldKeys = new HashSet<>();
            for (SAPFunctionDef def : sapFunctionDefs.values()) {
                for (var groups : def.conditionsByEndpoint().values()) {
                    for (var group : groups) {
                        for (var cond : group.conditions()) {
                            relevantObjFieldKeys.add(cond.objectKey() * 100000L + cond.fieldKey());
                        }
                    }
                }
            }
            Map<Long, List<AuthEntry>> roleAuthMap = accessDataDao.loadEntitlementObjects(request.securitySystemId(), relevantObjFieldKeys);

            // Resolve entitlements per user (merge accounts belonging to same user)
            List<UserAccess> users = resolveAllUsers(directAssignments, accountMetadata);
            log.info("Phase 1 complete: {} users resolved", users.size());
            logMemory("AFTER_PHASE1_DATA_LOAD");

            // ─── Phase 2: Evaluate Functions → BitSets (parallel) ──────────────
            log.info("Phase 2: Evaluating {} functions (parallel)...", functions.size());
            Map<String, BitSet> functionBitSets = new java.util.concurrent.ConcurrentHashMap<>();
            Map<Long, List<Long>> funcEndpointMap = new java.util.concurrent.ConcurrentHashMap<>();
            // Evidence map: "userIndex###funcKey" → evidence (collected during Phase 2, used in Phase 4)
            Map<String, List<FunctionEvidence>> evidenceMap = new java.util.concurrent.ConcurrentHashMap<>();
            functionEval.setGraphRefs(accessGraph.getGraph(), accessGraph.getReverseGraph());

            // Pre-build per-user auth index ONCE (reused across all function evaluations)
            // At 500K users this is ~1 GB but eliminates 72.5M HashMap constructions
            log.info("Phase 2: Pre-building auth indexes for {} users...", users.size());
            long authIndexStart = System.currentTimeMillis();
            List<Map<Long, List<AuthEntry>>> userAuthIndexes = new ArrayList<>(users.size());
            for (UserAccess user : users) {
                userAuthIndexes.add(functionEval.buildUserAuthIndex(user.resolvedEntitlements(), roleAuthMap));
            }
            log.info("Phase 2: Auth indexes built in {}ms", System.currentTimeMillis() - authIndexStart);
            logMemory("AFTER_AUTH_INDEX_BUILD");

            // Load TCD-field resolved tcodes (tcodes referenced by VALUE in function_objects with fieldkey=65)
            Map<Long, Map<Long, Set<Long>>> tcdResolvedTcodes = configDao.loadTcdFieldResolvedTcodes(sapFuncKeys, request.securitySystemId());
            functionEval.setTcdResolvedTcodes(tcdResolvedTcodes);

            // Load tcodeRoleMap equivalent: tcodes that have direct-assignment roles as parents
            // Used in Phase 4 to filter detail rows (old system only writes rows for tcodes in tcodeRoleMap)
            Set<Long> tcodesWithDirectParent = configDao.loadTcodesWithDirectRoleParent(request.securitySystemId());

            // Pre-compute allowed tcodes per function (old system's tcodeEvaluated behavior)
            // Functions processed in ID order; a tcode is only "allowed" for the FIRST function that contains it
            Map<Long, Set<Long>> allowedTcodesPerFunc = new HashMap<>();
            Set<Long> globalTcodeEvaluated = new HashSet<>();
            sapFunctionDefs.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey()) // process in function ID order
                    .forEach(entry -> {
                        Set<Long> funcTcodes = entry.getValue().conditionsByEndpoint().values().stream()
                                .flatMap(List::stream).flatMap(g -> g.conditions().stream())
                                .map(SAPFunctionDef.AuthCondition::tcodeKey).collect(java.util.stream.Collectors.toSet());
                        Set<Long> newTcodes = new HashSet<>(funcTcodes);
                        newTcodes.removeAll(globalTcodeEvaluated);
                        allowedTcodesPerFunc.put(entry.getKey(), newTcodes);
                        globalTcodeEvaluated.addAll(funcTcodes);
                    });
            functionEval.setAllowedTcodesPerFunc(allowedTcodesPerFunc);

            int totalFuncs = nonSAPConditions.size() + sapFunctionDefs.size();
            var counter = new java.util.concurrent.atomic.AtomicInteger(0);
            long phase2Start = System.currentTimeMillis();

            // Evaluate all functions in parallel using virtual threads
            List<UserAccess> finalUsers = users;
            Map<Long, List<AuthEntry>> finalRoleAuthMap = roleAuthMap;

            try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                List<java.util.concurrent.Future<?>> futures = new ArrayList<>();

                for (var entry : nonSAPConditions.entrySet()) {
                    long funcKey = entry.getKey();
                    var condition = entry.getValue();
                    futures.add(executor.submit(() -> {
                        long funcStart = System.currentTimeMillis();
                        // Load function exclusion query if present
                        SodFunction func = functions.get(funcKey);
                        Set<Long> excludedEnts = (func != null) ? configDao.loadFunctionExcludedEnts(func.exclusionQuery()) : Set.of();
                        BitSet bits = functionEval.evaluateNonSAP(condition, finalUsers, excludedEnts, excludedEntPairs, accessGraph);
                        functionBitSets.put(funcKey + "###0", bits);
                        funcEndpointMap.put(funcKey, List.of(0L));
                        int c = counter.incrementAndGet();
                        log.info("  [{}/{}] NonSAP func {} ({}): {} users, {}ms{}",
                                c, totalFuncs, funcKey,
                                func != null ? func.functionName() : "?",
                                bits.cardinality(), System.currentTimeMillis() - funcStart,
                                excludedEnts.isEmpty() ? "" : " [excluded=" + excludedEnts.size() + "]");
                    }));
                }

                for (var entry : sapFunctionDefs.entrySet()) {
                    long funcKey = entry.getKey();
                    SAPFunctionDef def = entry.getValue();
                    funcEndpointMap.put(funcKey, def.endpoints());

                    for (long ep : def.endpoints()) {
                        futures.add(executor.submit(() -> {
                            long funcStart = System.currentTimeMillis();
                            BitSet bits = functionEval.evaluateSAPWithEvidence(def, ep, finalUsers, finalRoleAuthMap, starTcodeKeys, evidenceMap, userAuthIndexes);
                            functionBitSets.put(funcKey + "###" + ep, bits);
                            int c = counter.incrementAndGet();
                            log.info("  [{}/{}] SAP func {}###ep{} ({}): {} users, {}ms",
                                    c, totalFuncs, funcKey, ep,
                                    functions.get(funcKey) != null ? functions.get(funcKey).functionName() : "?",
                                    bits.cardinality(), System.currentTimeMillis() - funcStart);
                        }));
                    }
                }

                // Wait for all to complete
                for (var future : futures) {
                    future.get();
                }
            } catch (Exception e) {
                throw new RuntimeException("Parallel function evaluation failed", e);
            }

            log.info("Phase 2 complete: {} BitSets built in {}ms", functionBitSets.size(),
                    System.currentTimeMillis() - phase2Start);
            log.info("Phase 2 complete: {} function×endpoint BitSets built", functionBitSets.size());
            logMemory("AFTER_PHASE2_PEAK");

            // ─── Phase 3: Detect Violations ────────────────────────────────────
            log.info("Phase 3: Detecting violations across {} risks...", risks.size());
            Map<String, BitSet> violationBitSets = violationDetection.detectViolations(
                    risks, functionBitSets, funcEndpointMap);

            int totalViolators = violationBitSets.values().stream().mapToInt(BitSet::cardinality).sum();
            log.info("Phase 3 complete: {} violations detected", totalViolators);

            // ─── Phase 4: Evidence + Persist ───────────────────────────────────
            log.info("Phase 4: Writing {} violations to DB...", totalViolators);
            long phase4Start = System.currentTimeMillis();
            persistViolations(violationBitSets, users, risks, jobId, rulesetKeys, nonSAPConditions, starTcodeKeys, request.securitySystemId(), evidenceMap, tcodesWithDirectParent, tcdResolvedTcodes);
            log.info("Phase 4 complete: {} violations written in {}ms", totalViolators,
                    System.currentTimeMillis() - phase4Start);

            // ─── Validation: Compare against existing SODRISKS ─────────────────
            log.info("Validation: Comparing against existing SODRISKS...");
            var validationReport = validationService.validate(violationBitSets, users, risks, rulesetKeys);
            log.info("Validation: correct={}, matches={}, FP={}, FN={}",
                    validationReport.isCorrect(), validationReport.matches(),
                    validationReport.falsePositives(), validationReport.falseNegatives());

            // Checksum validation on detail rows
            var jdbc2 = new org.springframework.jdbc.core.JdbcTemplate(accessDataDao.getDataSource());
            String rulesetCsv2 = rulesetKeys.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
            Long oldDetailCount = jdbc2.queryForObject("SELECT COUNT(*) FROM sodrisk_entitlement WHERE SODKEY IN (SELECT SODKEY FROM sodrisks WHERE RISKKEY IN (SELECT RISKID FROM risks WHERE RULESETKEY IN (" + rulesetCsv2 + ")))", Long.class);
            Long newDetailCount = jdbc2.queryForObject("SELECT COUNT(*) FROM sodrisk_entitlement_new_job WHERE SODKEY IN (SELECT SODKEY FROM sodrisks_new_job WHERE RISKKEY IN (SELECT RISKID FROM risks WHERE RULESETKEY IN (" + rulesetCsv2 + ")))", Long.class);
            Long oldChecksum = jdbc2.queryForObject("SELECT COALESCE(SUM(CRC32(CONCAT(FUNCTIONKEY,'#',TCODEKEY,'#',ASSOCIATEDSAPROLEKEY))),0) FROM sodrisk_entitlement WHERE SODKEY IN (SELECT SODKEY FROM sodrisks WHERE RISKKEY IN (SELECT RISKID FROM risks WHERE RULESETKEY IN (" + rulesetCsv2 + ")))", Long.class);
            Long newChecksum = jdbc2.queryForObject("SELECT COALESCE(SUM(CRC32(CONCAT(FUNCTIONKEY,'#',TCODEKEY,'#',ASSOCIATEDSAPROLEKEY))),0) FROM sodrisk_entitlement_new_job WHERE SODKEY IN (SELECT SODKEY FROM sodrisks_new_job WHERE RISKKEY IN (SELECT RISKID FROM risks WHERE RULESETKEY IN (" + rulesetCsv2 + ")))", Long.class);
            boolean detailMatch = oldDetailCount.equals(newDetailCount) && oldChecksum.equals(newChecksum);
            log.info("Validation detail rows: match={}, old_rows={}, new_rows={}, old_checksum={}, new_checksum={}",
                    detailMatch, oldDetailCount, newDetailCount, oldChecksum, newChecksum);

            // ─── Phase 5: Close stale violations (TODO) ────────────────────────
            log.info("Phase 5: Closing stale violations...");
            // TODO: Load existing, diff with current, close missing ones

            log.info("=== SOD Evaluation Complete (jobId={}) ===", jobId);
            logMemory("END");
            return EvaluationResult.success(jobId, directAssignments.size(), users.size(),
                    functions.size(), risks.size(), totalViolators, 0, 0, start, Instant.now());

        } catch (Exception e) {
            log.error("SOD Evaluation failed", e);
            return EvaluationResult.failure(jobId, start, e.getMessage());
        }
    }

    // ─── Private Helpers ───────────────────────────────────────────────────────

    private List<UserAccess> resolveAllUsers(Map<Long, List<Long>> directAssignments,
                                             Map<Long, long[]> accountMetadata) {
        // Group accounts by user
        Map<Long, List<Long>> userAccounts = new HashMap<>();

        directAssignments.keySet().forEach(accountKey -> {
            long[] meta = accountMetadata.get(accountKey);
            long userKey;
            if (meta != null && meta[0] != 0) {
                userKey = meta[0];  // mapped user
            } else {
                userKey = -1 * accountKey;  // unmapped: -1 * accountKey (matches ECMv4 behavior)
            }
            userAccounts.computeIfAbsent(userKey, k -> new ArrayList<>()).add(accountKey);
        });

        log.info("  Resolving entitlements for {} users ({} accounts)...", userAccounts.size(), directAssignments.size());

        // Resolve entitlements per user (merge all accounts)
        List<UserAccess> users = new ArrayList<>(userAccounts.size());
        long index = 0;
        int processed = 0;

        for (var entry : userAccounts.entrySet()) {
            long userKey = entry.getKey();
            List<Long> accountKeys = entry.getValue();

            // Merge resolved entitlements from all accounts
            Set<Long> mergedEnts = new HashSet<>();
            List<UserAccess.AccountAccess> accountAccesses = new ArrayList<>();

            for (long accountKey : accountKeys) {
                List<Long> direct = directAssignments.get(accountKey);
                long[] directArr = direct.stream().mapToLong(Long::longValue).toArray();
                long[] resolved = accessGraph.resolveEntitlements(directArr, maxDepth);

                for (long ent : resolved) mergedEnts.add(ent);

                long[] meta = accountMetadata.getOrDefault(accountKey, new long[]{0, 0, 0});
                accountAccesses.add(new UserAccess.AccountAccess(accountKey, meta[1], directArr));
            }

            long[] sortedEnts = mergedEnts.stream().mapToLong(Long::longValue).sorted().toArray();
            users.add(new UserAccess(userKey, index++, sortedEnts, accountAccesses, null));

            processed++;
            if (processed % 10000 == 0) {
                log.info("  Resolved {}/{} users (cache size: {})", processed, userAccounts.size(),
                        accessGraph.getResolvedCacheSize());
            }
        }

        return users;
    }

    private Set<Long> extractUniqueFunctionKeys(List<Risk> risks) {
        Set<Long> keys = new HashSet<>();
        risks.forEach(r -> keys.addAll(r.functionKeys()));
        return keys;
    }

    private Set<Long> filterByType(Map<Long, SodFunction> functions, SodFunction.FunctionType... types) {
        Set<SodFunction.FunctionType> typeSet = Set.of(types);
        Set<Long> result = new HashSet<>();
        functions.forEach((key, func) -> {
            if (typeSet.contains(func.type())) result.add(key);
        });
        return result;
    }

    private String buildAccountFilter(EvaluationRequest request) {
        StringBuilder filter = new StringBuilder();
        if (request.securitySystemId() != null) {
            filter.append("a.SYSTEMID = ").append(request.securitySystemId());
        }
        if (request.accountQuery() != null && !request.accountQuery().isBlank()) {
            if (!filter.isEmpty()) filter.append(" AND ");
            filter.append(request.accountQuery());
        }
        return filter.isEmpty() ? null : filter.toString();
    }

    private void persistViolations(Map<String, BitSet> violationBitSets, List<UserAccess> users,
                                   List<Risk> risks, long jobId, List<Long> rulesetKeys,
                                   Map<Long, NonSAPCondition> nonSAPConditions, Set<Long> starTcodeKeys,
                                   Long securitySystemId, Map<String, List<FunctionEvidence>> evidenceMap,
                                   Set<Long> tcodesWithDirectParent, Map<Long, Map<Long, Set<Long>>> tcdResolvedTcodes) {
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(
                accessDataDao.getDataSource());
        String rulesetCsv = rulesetKeys.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));

        // --- Step 1: Load existing content hashes per violation identity (for delta detection) ---
        long deltaStart = System.currentTimeMillis();
        Map<String, long[]> oldHashes = new HashMap<>(); // "userKey###riskId###epKey" → [rowCount, hashSum]
        try {
            jdbc.query("SELECT sr.USERIDENTIFIER, sr.RISKKEY, sr.ENDPOINTKEY, COUNT(*) as CNT, " +
                    "COALESCE(SUM(CRC32(CONCAT(se.FUNCTIONKEY,'#',se.TCODEKEY,'#',se.ASSOCIATEDSAPROLEKEY,'#',se.ACCOUNTKEY))),0) as HASH " +
                    "FROM sodrisk_entitlement_new_job se JOIN sodrisks_new_job sr ON se.SODKEY = sr.SODKEY " +
                    "WHERE sr.RISKKEY IN (SELECT RISKID FROM risks WHERE RULESETKEY IN (" + rulesetCsv + ")) " +
                    "GROUP BY sr.USERIDENTIFIER, sr.RISKKEY, sr.ENDPOINTKEY", rs -> {
                String key = rs.getLong(1) + "###" + rs.getLong(2) + "###" + rs.getLong(3);
                oldHashes.put(key, new long[]{rs.getLong(4), rs.getLong(5)});
            });
        } catch (Exception e) {
            log.info("No existing detail rows found (first run or table empty)");
        }
        log.info("Phase 4: Loaded {} existing violation hashes in {}ms", oldHashes.size(), System.currentTimeMillis() - deltaStart);

        // --- Step 2: Upsert summary rows (sodrisks_new_job) — keep existing SODKEYs stable ---
        // Load existing SODKEYs by violation identity
        Map<String, Long> existingSodKeys = new HashMap<>();
        jdbc.query("SELECT SODKEY, USERIDENTIFIER, RISKKEY, ENDPOINTKEY FROM sodrisks_new_job WHERE RISKKEY IN (SELECT RISKID FROM risks WHERE RULESETKEY IN (" + rulesetCsv + "))",
                rs -> { existingSodKeys.put(rs.getLong(2) + "###" + rs.getLong(3) + "###" + rs.getLong(4), rs.getLong(1)); });
        log.info("Phase 4: {} existing SODKEYs loaded (stable across runs)", existingSodKeys.size());

        // Insert only NEW violations (not already in table)
        String insertSodRisk = "INSERT INTO sodrisks_new_job (RISKKEY, RISKCODE, USERIDENTIFIER, ENDPOINTKEY, STATUS, FIRSTIMPORTDATE, LASTIMPORTDATE, JOBID) VALUES (?,?,?,?,1,NOW(),NOW(),?)";
        List<Object[]> riskBatch = new java.util.ArrayList<>(1000);

        Map<Long, List<Map.Entry<String, BitSet>>> bitSetsByRiskSummary = new HashMap<>();
        for (var e : violationBitSets.entrySet()) {
            long rid = Long.parseLong(e.getKey().split("###")[0]);
            bitSetsByRiskSummary.computeIfAbsent(rid, k -> new ArrayList<>()).add(e);
        }

        for (Risk risk : risks) {
            List<Map.Entry<String, BitSet>> entries = bitSetsByRiskSummary.get(risk.riskId());
            if (entries == null) continue;
            for (var entry : entries) {
                String[] parts = entry.getKey().split("###");
                long riskId = Long.parseLong(parts[0]);
                long endpointKey = Long.parseLong(parts[1]);
                BitSet bits = entry.getValue();
                for (int i = bits.nextSetBit(0); i >= 0; i = bits.nextSetBit(i + 1)) {
                    UserAccess user = users.get(i);
                    String violationId = user.userKey() + "###" + riskId + "###" + endpointKey;
                    if (!existingSodKeys.containsKey(violationId)) {
                        // New violation — insert
                        riskBatch.add(new Object[]{riskId, risk.riskName(), user.userKey(), endpointKey, jobId});
                        if (riskBatch.size() >= 1000) {
                            jdbc.batchUpdate(insertSodRisk, riskBatch);
                            riskBatch.clear();
                        }
                    }
                }
            }
        }
        if (!riskBatch.isEmpty()) {
            jdbc.batchUpdate(insertSodRisk, riskBatch);
            riskBatch.clear();
        }

        // --- Step 3: Build sodKeyMap (existing + newly inserted) ---
        Map<String, Long> sodKeyMap = new java.util.HashMap<>(existingSodKeys);
        // Fetch newly generated SODKEYs
        jdbc.query("SELECT SODKEY, USERIDENTIFIER, RISKKEY, ENDPOINTKEY FROM sodrisks_new_job WHERE JOBID = ?",
                rs -> { sodKeyMap.put(rs.getLong(2) + "###" + rs.getLong(3) + "###" + rs.getLong(4), rs.getLong(1)); }, jobId);

        // Delete stale violations from sodrisks_new_job (violations that no longer exist)
        Set<String> currentViolationIds = new HashSet<>();
        for (var bsEntry : violationBitSets.entrySet()) {
            String[] parts = bsEntry.getKey().split("###");
            long riskId = Long.parseLong(parts[0]);
            long endpointKey = Long.parseLong(parts[1]);
            BitSet bits = bsEntry.getValue();
            for (int i = bits.nextSetBit(0); i >= 0; i = bits.nextSetBit(i + 1)) {
                currentViolationIds.add(users.get(i).userKey() + "###" + riskId + "###" + endpointKey);
            }
        }
        List<Long> staleSodKeys = new ArrayList<>();
        for (var entry : existingSodKeys.entrySet()) {
            if (!currentViolationIds.contains(entry.getKey())) {
                staleSodKeys.add(entry.getValue());
            }
        }
        if (!staleSodKeys.isEmpty()) {
            String staleCsv = staleSodKeys.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
            jdbc.update("DELETE FROM sodrisk_entitlement_new_job WHERE SODKEY IN (" + staleCsv + ")");
            jdbc.update("DELETE FROM sodrisks_new_job WHERE SODKEY IN (" + staleCsv + ")");
            log.info("Phase 4: Closed {} stale violations", staleSodKeys.size());
        }

        // --- Step 4: Generate detail rows + compute new hashes per violation identity ---
        int totalViolationEntries = violationBitSets.values().stream().mapToInt(BitSet::cardinality).sum();
        log.info("Phase 4: Computing detail rows for {} violations ({} evidence entries)...", totalViolationEntries, evidenceMap.size());
        long phase4DetailStart = System.currentTimeMillis();

        // Collect all detail rows grouped by violation identity, compute hash
        Map<String, List<String>> rowsByViolation = new HashMap<>(); // "userKey###riskId###epKey" → rows
        Map<String, long[]> newHashes = new HashMap<>(); // "userKey###riskId###epKey" → [rowCount, hashSum]

        Map<Long, List<Map.Entry<String, BitSet>>> bitSetsByRisk = new HashMap<>();
        for (var bsEntry : violationBitSets.entrySet()) {
            long riskId = Long.parseLong(bsEntry.getKey().split("###")[0]);
            bitSetsByRisk.computeIfAbsent(riskId, k -> new ArrayList<>()).add(bsEntry);
        }

        for (Risk risk : risks) {
            List<Map.Entry<String, BitSet>> riskEntries = bitSetsByRisk.get(risk.riskId());
            if (riskEntries == null) continue;
            for (var bsEntry : riskEntries) {
                String[] parts = bsEntry.getKey().split("###");
                BitSet bits = bsEntry.getValue();
                long endpointKey = Long.parseLong(parts[1]);
                if (bits.isEmpty()) continue;

                for (int i = bits.nextSetBit(0); i >= 0; i = bits.nextSetBit(i + 1)) {
                    UserAccess user = users.get(i);
                    Long sodKey = sodKeyMap.get(user.userKey() + "###" + risk.riskId() + "###" + endpointKey);
                    if (sodKey == null) continue;
                    String violationId = user.userKey() + "###" + risk.riskId() + "###" + endpointKey;

                    for (long funcKey : risk.functionKeys()) {
                        List<FunctionEvidence> evidences = evidenceMap.get(i + "###" + funcKey);
                        if (evidences != null) {
                            for (var ev : evidences) {
                                if (ev.endpointKey() == endpointKey || ev.endpointKey() == 0) {
                                    String row = sodKey + "\t" + ev.accountKey() + "\t" + ev.assocSapRole() + "\t" + funcKey + "\t" + ev.tcodeKey() + "\t2\t" + ev.directRole();
                                    rowsByViolation.computeIfAbsent(violationId, k -> new ArrayList<>()).add(row);
                                    long h = crc32(funcKey + "#" + ev.tcodeKey() + "#" + ev.assocSapRole() + "#" + ev.accountKey());
                                    newHashes.merge(violationId, new long[]{1, h}, (a, b) -> new long[]{a[0] + b[0], a[1] + b[1]});
                                }
                            }
                        } else {
                            // NonSAP fallback — full path evidence per account
                            NonSAPCondition condition = nonSAPConditions.get(funcKey);
                            if (condition != null) {
                                List<Long> funcEntKeys = getFunctionEntitlementKeys(funcKey);
                                for (var acct : user.accounts()) {
                                    long[] directs = acct.directAssignments();
                                    for (long funcEntKey : funcEntKeys) {
                                        if (java.util.Arrays.binarySearch(user.resolvedEntitlements(), funcEntKey) >= 0) {
                                            List<Long> path = accessGraph.findPath(directs, funcEntKey, maxDepth);
                                            long directRole = path.isEmpty() ? 0 : path.getFirst();
                                            long assocRole = path.size() >= 2 ? path.get(path.size() - 2) : directRole;
                                            String pathCsv = path.size() > 1
                                                    ? path.subList(0, path.size() - 1).stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","))
                                                    : String.valueOf(directRole);
                                            String row = sodKey + "\t" + acct.accountKey() + "\t" + assocRole + "\t" + funcKey + "\t" + funcEntKey + "\t2\t" + pathCsv;
                                            rowsByViolation.computeIfAbsent(violationId, k -> new ArrayList<>()).add(row);
                                            long h = crc32(funcKey + "#" + funcEntKey + "#" + assocRole + "#" + acct.accountKey());
                                            newHashes.merge(violationId, new long[]{1, h}, (a, b) -> new long[]{a[0] + b[0], a[1] + b[1]});
                                        }
                                    }
                                    break;
                                }
                            }
                        }
                    }
                }
            }
        }

        // --- Step 5: Delta detection ---
        Set<String> toInsert = new HashSet<>();
        Set<String> toDelete = new HashSet<>();
        int unchanged = 0;

        for (var entry : newHashes.entrySet()) {
            String violationId = entry.getKey();
            long[] newVal = entry.getValue();
            long[] oldVal = oldHashes.remove(violationId);

            if (oldVal == null) {
                toInsert.add(violationId);  // brand new
            } else if (oldVal[0] == newVal[0] && oldVal[1] == newVal[1]) {
                unchanged++;  // identical — skip
            } else {
                toDelete.add(violationId);  // changed — delete old, insert new
                toInsert.add(violationId);
            }
        }
        // Remaining oldHashes = stale (violation closed)
        toDelete.addAll(oldHashes.keySet());

        log.info("Phase 4: Delta detection: {} unchanged, {} to insert, {} to delete (of {} total violations)",
                unchanged, toInsert.size(), toDelete.size(), newHashes.size());

        // --- Step 6: Delete changed detail rows (SODKEYs are stable now, delta works) ---
        if (!toDelete.isEmpty()) {
            // Find SODKEYs for changed/stale violations
            List<Long> deleteSodKeys = new ArrayList<>();
            for (String vid : toDelete) {
                Long sk = sodKeyMap.get(vid);
                if (sk != null) deleteSodKeys.add(sk);
            }
            if (!deleteSodKeys.isEmpty()) {
                String deleteCsv = deleteSodKeys.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
                jdbc.update("DELETE FROM sodrisk_entitlement_new_job WHERE SODKEY IN (" + deleteCsv + ")");
                log.info("Phase 4: Deleted detail rows for {} changed violations", deleteSodKeys.size());
            }
        }

        // --- Step 7: Write only new/changed detail rows via LOAD DATA ---
        if (!toInsert.isEmpty()) {
            java.io.File tempFile;
            try {
                String sharedVolume = System.getenv("SOD_SHARED_VOLUME");
                if (sharedVolume != null && !sharedVolume.isBlank()) {
                    tempFile = new java.io.File(sharedVolume, "sod_detail_" + jobId + ".csv");
                } else {
                    tempFile = java.io.File.createTempFile("sod_detail_", ".csv");
                }
                int rowsWritten = 0;
                try (var writer = new java.io.BufferedWriter(new java.io.FileWriter(tempFile), 1 << 20)) {
                    for (String violationId : toInsert) {
                        List<String> rows = rowsByViolation.get(violationId);
                        if (rows != null) {
                            for (String row : rows) {
                                writer.write(row);
                                writer.newLine();
                                rowsWritten++;
                            }
                        }
                    }
                }
                log.info("Phase 4: Wrote {} detail rows to file in {}ms, loading into DB...",
                        rowsWritten, System.currentTimeMillis() - phase4DetailStart);

                // Bulk load
                long loadStart = System.currentTimeMillis();
                String loadSql = sharedVolume != null && !sharedVolume.isBlank()
                        ? "LOAD DATA INFILE '" + tempFile.getAbsolutePath().replace("\\", "\\\\") + "'"
                        : "LOAD DATA LOCAL INFILE '" + tempFile.getAbsolutePath().replace("\\", "\\\\") + "'";
                jdbc.execute(loadSql + " INTO TABLE sodrisk_entitlement_new_job " +
                        "FIELDS TERMINATED BY '\\t' LINES TERMINATED BY '\\n' " +
                        "(SODKEY, ACCOUNTKEY, ASSOCIATEDSAPROLEKEY, FUNCTIONKEY, TCODEKEY, SODTYPE, PARENTROLEKEYASCSV)");
                tempFile.delete();
                log.info("Phase 4: Loaded into DB in {}ms", System.currentTimeMillis() - loadStart);
            } catch (java.io.IOException e) {
                throw new RuntimeException("Failed to write detail rows file", e);
            }
        } else {
            log.info("Phase 4: No detail rows to write (all unchanged)");
        }

        log.info("Phase 4: Complete in {}ms", System.currentTimeMillis() - phase4DetailStart);
    }

    /** CRC32 hash for delta detection */
    private long crc32(String input) {
        var crc = new java.util.zip.CRC32();
        crc.update(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return crc.getValue();
    }

    /** Cache for function entitlement keys (avoid repeated DB queries). */
    private final Map<Long, List<Long>> funcEntKeysCache = new HashMap<>();

    /** Get function entitlement keys for a NonSAP function from function_entitlements table. */
    private List<Long> getFunctionEntitlementKeys(long funcKey) {
        return funcEntKeysCache.computeIfAbsent(funcKey, k -> {
            var jdbc2 = new org.springframework.jdbc.core.JdbcTemplate(accessDataDao.getDataSource());
            return jdbc2.queryForList(
                    "SELECT ENTITLEMENT_VALUEKEY FROM function_entitlements WHERE FUNCTIONKEY = ? ORDER BY CONDITIONPOSITION",
                    Long.class, k);
        });
    }

    /** Find which direct assignment provides access to a given entitlement. */
    private long findParentRole(long[] directAssignments, long targetEnt, UserAccess user) {
        return accessGraph.findAncestorIn(targetEnt, directAssignments);
    }

    /** Recursively extract entitlement keys from a NonSAP condition tree. */
    private void collectLeafNodes(NonSAPCondition condition, Set<Long> out) {
        if (condition instanceof NonSAPCondition.HasEntitlement h) {
            out.add(h.entitlementKey());
        } else if (condition instanceof NonSAPCondition.And a) {
            collectLeafNodes(a.left(), out);
            collectLeafNodes(a.right(), out);
        } else if (condition instanceof NonSAPCondition.Or o) {
            collectLeafNodes(o.left(), out);
            collectLeafNodes(o.right(), out);
        }
    }

    private void logMemory(String phase) {
        Runtime rt = Runtime.getRuntime();
        long used = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        long max = rt.maxMemory() / (1024 * 1024);
        long total = rt.totalMemory() / (1024 * 1024);
        log.info("MEMORY_{} Used: {} MB, Total: {} MB, Max: {} MB", phase, used, total, max);
    }
}
