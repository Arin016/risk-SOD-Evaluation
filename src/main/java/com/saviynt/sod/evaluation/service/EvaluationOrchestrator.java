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
            accessGraph.loadGraph(request.securitySystemId());

            String accountFilter = buildAccountFilter(request);
            Map<Long, List<Long>> directAssignments = accessDataDao.loadAccountEntitlements(accountFilter, request.entitlementQuery());
            Map<Long, long[]> accountMetadata = accessDataDao.loadAccountMetadata(accountFilter);
            Map<Long, List<AuthEntry>> roleAuthMap = accessDataDao.loadEntitlementObjects(request.securitySystemId());

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
                            BitSet bits = functionEval.evaluateSAPWithEvidence(def, ep, finalUsers, finalRoleAuthMap, starTcodeKeys, evidenceMap);
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

        // --- Step 1: Clear previous run for this ruleset ---
        String rulesetCsv = rulesetKeys.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
        jdbc.update("DELETE FROM sodrisk_entitlement_new_job WHERE SODKEY IN (SELECT SODKEY FROM sodrisks_new_job WHERE RISKKEY IN (SELECT RISKID FROM risks WHERE RULESETKEY IN (" + rulesetCsv + ")))");
        jdbc.update("DELETE FROM sodrisks_new_job WHERE RISKKEY IN (SELECT RISKID FROM risks WHERE RULESETKEY IN (" + rulesetCsv + "))");

        // --- Step 2: Write summary rows ---
        String insertSodRisk = "INSERT INTO sodrisks_new_job (RISKKEY, RISKCODE, USERIDENTIFIER, ENDPOINTKEY, STATUS, FIRSTIMPORTDATE, LASTIMPORTDATE, JOBID) VALUES (?,?,?,?,1,NOW(),NOW(),?)";
        List<Object[]> riskBatch = new java.util.ArrayList<>(1000);

        // Pre-index for O(1) lookup
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
                    riskBatch.add(new Object[]{riskId, risk.riskName(), user.userKey(), endpointKey, jobId});
                    if (riskBatch.size() >= 1000) {
                        jdbc.batchUpdate(insertSodRisk, riskBatch);
                        riskBatch.clear();
                    }
                }
            }
        }
        if (!riskBatch.isEmpty()) {
            jdbc.batchUpdate(insertSodRisk, riskBatch);
            riskBatch.clear();
        }

        // --- Step 3: Fetch generated SODKEYs ---
        Map<String, Long> sodKeyMap = new java.util.HashMap<>();
        jdbc.query("SELECT SODKEY, USERIDENTIFIER, RISKKEY, ENDPOINTKEY FROM sodrisks_new_job WHERE JOBID = ?",
                rs -> { sodKeyMap.put(rs.getLong(2) + "###" + rs.getLong(3) + "###" + rs.getLong(4), rs.getLong(1)); }, jobId);

        // --- Step 4: Write detail rows using LOAD DATA INFILE (10-50x faster than batch INSERT) ---
        int detailProgress = 0;
        int totalViolationEntries = violationBitSets.values().stream().mapToInt(BitSet::cardinality).sum();
        log.info("Phase 4: Writing detail rows for {} violations using pre-computed evidence ({} entries)...", totalViolationEntries, evidenceMap.size());
        long phase4DetailStart = System.currentTimeMillis();

        // Write to temp file
        java.io.File tempFile;
        try {
            tempFile = java.io.File.createTempFile("sod_detail_", ".csv");
            try (var writer = new java.io.BufferedWriter(new java.io.FileWriter(tempFile), 1 << 20)) {
            // Pre-index violationBitSets by riskId for O(1) lookup
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

                        for (long funcKey : risk.functionKeys()) {
                            List<FunctionEvidence> evidences = evidenceMap.get(i + "###" + funcKey);
                            if (evidences != null) {
                                for (var ev : evidences) {
                                    if (ev.endpointKey() == endpointKey || ev.endpointKey() == 0) {
                                        writer.write(sodKey + "\t" + ev.accountKey() + "\t" + ev.assocSapRole() + "\t" + funcKey + "\t" + ev.tcodeKey() + "\t2\t" + ev.directRole());
                                        writer.newLine();
                                        detailProgress++;
                                    }
                                }
                            } else {
                                // NonSAP fallback
                                NonSAPCondition condition = nonSAPConditions.get(funcKey);
                                if (condition != null) {
                                    long[] directs = user.accounts().isEmpty() ? new long[0] : user.accounts().getFirst().directAssignments();
                                    List<Long> funcEntKeys = getFunctionEntitlementKeys(funcKey);
                                    for (var acct : user.accounts()) {
                                        for (long funcEntKey : funcEntKeys) {
                                            if (java.util.Arrays.binarySearch(user.resolvedEntitlements(), funcEntKey) >= 0) {
                                                long pr = accessGraph.findAncestorIn(funcEntKey, directs);
                                                writer.write(sodKey + "\t" + acct.accountKey() + "\t" + pr + "\t" + funcKey + "\t" + funcEntKey + "\t2\t" + pr);
                                                writer.newLine();
                                                detailProgress++;
                                            }
                                        }
                                        break;
                                    }
                                }
                            }
                        }
                    }
                } // end bsEntry loop
            }
        }
        log.info("Phase 4: Wrote {} detail rows to temp file in {}ms, loading into DB...", detailProgress, System.currentTimeMillis() - phase4DetailStart);
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to write detail rows temp file", e);
        }

        // Bulk load via LOAD DATA LOCAL INFILE
        long loadStart = System.currentTimeMillis();
        jdbc.execute("LOAD DATA LOCAL INFILE '" + tempFile.getAbsolutePath().replace("\\", "\\\\") + "' INTO TABLE sodrisk_entitlement_new_job " +
                "FIELDS TERMINATED BY '\\t' LINES TERMINATED BY '\\n' " +
                "(SODKEY, ACCOUNTKEY, ASSOCIATEDSAPROLEKEY, FUNCTIONKEY, TCODEKEY, SODTYPE, PARENTROLEKEYASCSV)");
        tempFile.delete();
        log.info("Phase 4: Detail rows loaded into DB in {}ms. Total: {}ms", System.currentTimeMillis() - loadStart, System.currentTimeMillis() - phase4DetailStart);
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

    private void logMemory(String phase) {
        Runtime rt = Runtime.getRuntime();
        long used = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        long max = rt.maxMemory() / (1024 * 1024);
        long total = rt.totalMemory() / (1024 * 1024);
        log.info("MEMORY_{} Used: {} MB, Total: {} MB, Max: {} MB", phase, used, total, max);
    }
}
