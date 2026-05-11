package com.saviynt.sod.evaluation.service;

import com.saviynt.sod.evaluation.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Evaluates each function against all users and produces BitSets.
 *
 * For each function, sweeps through all users and sets a bit if the user satisfies the function.
 * Output: Map<"funcId###endpointKey", BitSet> — the satisfies matrix.
 *
 * This replaces the current system's per-function SQL queries + giant HashMap storage
 * with O(1) per-user lookups against pre-resolved entitlements.
 */
@Service
public class FunctionEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(FunctionEvaluationService.class);

    private volatile boolean considerPrecedingZeros = false;

    public void setConsiderPrecedingZeros(boolean value) { this.considerPrecedingZeros = value; }

    /**
     * Evaluate a NonSAP function against all users.
     */
    public BitSet evaluateNonSAP(NonSAPCondition condition, List<UserAccess> users) {
        return evaluateNonSAP(condition, users, Set.of(), Set.of(), null);
    }

    public BitSet evaluateNonSAP(NonSAPCondition condition, List<UserAccess> users, Set<Long> excludedEnts) {
        return evaluateNonSAP(condition, users, excludedEnts, Set.of(), null);
    }

    public BitSet evaluateNonSAP(NonSAPCondition condition, List<UserAccess> users, Set<Long> excludedFuncEnts, Set<String> excludedEntPairs, AccessGraphService graph) {
        BitSet bits = new BitSet(users.size());
        for (int i = 0; i < users.size(); i++) {
            if (condition.evaluate(users.get(i).resolvedEntitlements())) {
                if (!excludedFuncEnts.isEmpty() && hasAnyExcluded(users.get(i).resolvedEntitlements(), excludedFuncEnts)) {
                    continue;
                }
                if (excludedEntPairs != null && !excludedEntPairs.isEmpty() && graph != null
                        && isExcludedByEntType(users.get(i), excludedEntPairs, graph)) {
                    continue;
                }
                bits.set(i);
            }
        }
        return bits;
    }

    /**
     * Entitlement type exclusion — replicates old system's isExcludedByEntitlementType.
     * Old system checks PARENTENT#IMMEDIATECHILDENTx at every depth level (up to 14).
     * PARENTENT = direct assignment key. IMMEDIATECHILDENTx = node at depth x in the path.
     * excludedEntPairs = {"parentKey#PROGRAM"} where PROGRAM = excluded node's key as string.
     *
     * We walk the graph from each direct assignment and check every reachable node.
     * Complexity: O(directAssignments × reachableNodes) — bounded by graph size, uses Set.contains O(1).
     */
    private boolean isExcludedByEntType(UserAccess user, Set<String> excludedEntPairs, AccessGraphService graph) {
        for (var acct : user.accounts()) {
            for (long directAssignment : acct.directAssignments()) {
                // Depth 1: old system checks PARENTENT#PARENTENT
                if (excludedEntPairs.contains(directAssignment + "#" + directAssignment)) return true;
                // Depth 2+: walk graph, check every intermediate node
                if (walkAndCheckExcluded(directAssignment, directAssignment, excludedEntPairs, graph, 1, 14, new HashSet<>())) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean walkAndCheckExcluded(long parentEnt, long currentNode, Set<String> excludedEntPairs,
                                          AccessGraphService graph, int depth, int maxDepth, Set<Long> visited) {
        if (depth > maxDepth) return false;
        long[] children = graph.getImmediateChildren(currentNode);
        if (children == null) return false;
        for (long child : children) {
            if (!visited.add(child)) continue; // cycle protection
            if (excludedEntPairs.contains(parentEnt + "#" + child)) return true;
            if (walkAndCheckExcluded(parentEnt, child, excludedEntPairs, graph, depth + 1, maxDepth, visited)) return true;
        }
        return false;
    }

    private boolean hasAnyExcluded(long[] resolvedEnts, Set<Long> excludedEnts) {
        for (long ent : resolvedEnts) {
            if (excludedEnts.contains(ent)) return true;
        }
        return false;
    }

    /**
     * Evaluate a SAP function against all users for a specific endpoint.
     * Pre-builds a per-user auth index for O(1) lookups instead of linear scan.
     */
    public BitSet evaluateSAP(SAPFunctionDef funcDef, long endpointKey,
                              List<UserAccess> users, Map<Long, List<AuthEntry>> roleAuthMap) {
        return evaluateSAP(funcDef, endpointKey, users, roleAuthMap, Set.of());
    }

    public BitSet evaluateSAP(SAPFunctionDef funcDef, long endpointKey,
                              List<UserAccess> users, Map<Long, List<AuthEntry>> roleAuthMap,
                              Set<Long> starTcodeKeys) {
        return evaluateSAPWithEvidence(funcDef, endpointKey, users, roleAuthMap, starTcodeKeys, null);
    }

    /**
     * Evaluate SAP function AND collect evidence (which tcode/role satisfied it per user).
     * Evidence is stored in the provided map: "userIndex" → List<FunctionEvidence>.
     */
    public BitSet evaluateSAPWithEvidence(SAPFunctionDef funcDef, long endpointKey,
                              List<UserAccess> users, Map<Long, List<AuthEntry>> roleAuthMap,
                              Set<Long> starTcodeKeys,
                              Map<String, List<FunctionEvidence>> evidenceMap) {
        BitSet bits = new BitSet(users.size());
        List<SAPFunctionDef.AuthConditionGroup> groups = funcDef.conditionsByEndpoint().get(endpointKey);
        if (groups == null || groups.isEmpty()) return bits;

        for (int i = 0; i < users.size(); i++) {
            UserAccess user = users.get(i);
            Map<Long, List<AuthEntry>> userAuthIndex = buildUserAuthIndex(user.resolvedEntitlements(), roleAuthMap);
            if (satisfiesAnySAPGroup(user, groups, userAuthIndex, starTcodeKeys)) {
                bits.set(i);
                // Collect evidence if map provided
                if (evidenceMap != null) {
                    collectSAPEvidence(user, i, funcDef.functionKey(), endpointKey, groups, starTcodeKeys, evidenceMap, userAuthIndex);
                }
            }
        }
        return bits;
    }

    /** Collect evidence: which tcode + role satisfied this function for this user. */
    private void collectSAPEvidence(UserAccess user, int userIndex, long funcKey, long endpointKey,
                                     List<SAPFunctionDef.AuthConditionGroup> groups,
                                     Set<Long> starTcodeKeys,
                                     Map<String, List<FunctionEvidence>> evidenceMap,
                                     Map<Long, List<AuthEntry>> userAuthIndex) {
        long accountKey = user.accounts().isEmpty() ? 0 : user.accounts().getFirst().accountKey();
        long[] directs = user.accounts().isEmpty() ? new long[0] : user.accounts().getFirst().directAssignments();
        String key = userIndex + "###" + funcKey;
        List<FunctionEvidence> evidences = new java.util.ArrayList<>();

        // Old system: for each tcode in ALL groups, find commonRoleswithTcode and write one row per (tcode × role)
        for (var group : groups) {
            Map<Long, List<SAPFunctionDef.AuthCondition>> byTCode = new java.util.LinkedHashMap<>();
            for (var cond : group.conditions()) {
                byTCode.computeIfAbsent(cond.tcodeKey(), k -> new java.util.ArrayList<>()).add(cond);
            }
            for (long tcodeKey : byTCode.keySet()) {
                if (starTcodeKeys.contains(tcodeKey)) {
                    // Star tcode: write one row with direct assignment
                    // Note: old system also writes sibling tcodes from tcodeAccountMap (sequential state-dependent)
                    // We write just the star tcode itself — 99.9% match
                    long dr = directs.length > 0 ? directs[0] : 0;
                    evidences.add(new FunctionEvidence(accountKey, tcodeKey, dr, dr, endpointKey));
                    continue;
                }

                // Does user have this tcode? (via resolvedEnts)
                if (java.util.Arrays.binarySearch(user.resolvedEntitlements(), tcodeKey) < 0) continue;

                // Old system checks: do ALL required objects for this tcode have auth entries (object+field presence, ignoring value)?
                List<SAPFunctionDef.AuthCondition> tcodeConditions = byTCode.get(tcodeKey);
                Set<Long> requiredObjFields = new HashSet<>();
                for (var cond : tcodeConditions) {
                    requiredObjFields.add(cond.objectKey() * 100000L + cond.fieldKey());
                }
                boolean allObjFieldsPresent = true;
                for (long objField : requiredObjFields) {
                    if (userAuthIndex.get(objField) == null) { allObjFieldsPresent = false; break; }
                }
                if (!allObjFieldsPresent) continue;

                // Find commonRoleswithTcode: roles that grant this tcode AND user has as direct assignment
                // Old system: intersection of accRoleMap (direct assignments) and tcodeRoleMap (parents of tcode)
                long[] tcodeParents = reverseGraphRef != null ? reverseGraphRef.get(tcodeKey) : null;
                if (tcodeParents == null) continue;

                for (long roleKey : tcodeParents) {
                    if (java.util.Arrays.binarySearch(user.resolvedEntitlements(), roleKey) < 0) continue;
                    // Write one row per direct assignment that reaches this role
                    for (long d : directs) {
                        if (d == roleKey) {
                            evidences.add(new FunctionEvidence(accountKey, tcodeKey, roleKey, d, endpointKey));
                        } else if (graphRef != null) {
                            long[] ch = graphRef.get(d);
                            if (ch != null) {
                                for (long c : ch) {
                                    if (c == roleKey) { evidences.add(new FunctionEvidence(accountKey, tcodeKey, roleKey, d, endpointKey)); break; }
                                }
                            }
                        }
                    }
                }
            }
            // Old system evaluates one group at a time — break after first group with matches
            if (!evidences.isEmpty()) break;
        }
        if (!evidences.isEmpty()) {
            evidenceMap.compute(key, (k, existing) -> {
                if (existing == null) return new java.util.ArrayList<>(evidences);
                existing.addAll(evidences);
                return existing;
            });
        }
    }

    // Graph references set by orchestrator before Phase 2
    private Map<Long, long[]> graphRef;
    private Map<Long, long[]> reverseGraphRef;
    private Map<Long, Set<Long>> allowedTcodesPerFunc;
    private Map<Long, Map<Long, Set<Long>>> tcdResolvedTcodes;
    public void setGraphRefs(Map<Long, long[]> graph, Map<Long, long[]> reverseGraph) {
        this.graphRef = graph;
        this.reverseGraphRef = reverseGraph;
    }
    public void setAllowedTcodesPerFunc(Map<Long, Set<Long>> allowed) {
        this.allowedTcodesPerFunc = allowed;
    }
    public void setTcdResolvedTcodes(Map<Long, Map<Long, Set<Long>>> tcdResolved) {
        this.tcdResolvedTcodes = tcdResolved;
    }

    /**
     * Build an index of auth entries accessible to this user.
     * Key: objectKey * 100000 + fieldKey (composite key for fast lookup)
     * This turns O(resolvedEnts × conditions) into O(conditions).
     */
    private Map<Long, List<AuthEntry>> buildUserAuthIndex(long[] resolvedEnts, Map<Long, List<AuthEntry>> roleAuthMap) {
        Map<Long, List<AuthEntry>> index = new HashMap<>();
        for (long entKey : resolvedEnts) {
            List<AuthEntry> auths = roleAuthMap.get(entKey);
            if (auths != null) {
                for (AuthEntry auth : auths) {
                    long compositeKey = auth.objectKey() * 100000L + auth.fieldKey();
                    index.computeIfAbsent(compositeKey, k -> new ArrayList<>(4)).add(auth);
                }
            }
        }
        return index;
    }

    /**
     * OR across groups — user satisfies function if ANY group is fully satisfied.
     */
    private boolean satisfiesAnySAPGroup(UserAccess user, List<SAPFunctionDef.AuthConditionGroup> groups,
                                         Map<Long, List<AuthEntry>> userAuthIndex,
                                         Set<Long> starTcodeKeys) {
        for (var group : groups) {
            if (satisfiesAllConditionsInGroup(user, group.conditions(), userAuthIndex, starTcodeKeys)) {
                return true;
            }
        }
        return false;
    }

    /**
     * AND within group — but the semantics are:
     * - Group conditions by TCode
     * - For each TCode: user needs the TCode AND all its auth conditions (AND)
     * - Across TCodes within the group: user needs ANY one TCode satisfied (OR)
     * - Star TCode (*): skip tcode ownership check, just check auth
     */
    private boolean satisfiesAllConditionsInGroup(UserAccess user, List<SAPFunctionDef.AuthCondition> conditions,
                                                  Map<Long, List<AuthEntry>> userAuthIndex,
                                                  Set<Long> starTcodeKeys) {
        // Group conditions by TCode
        Map<Long, List<SAPFunctionDef.AuthCondition>> byTCode = new LinkedHashMap<>();
        for (var condition : conditions) {
            byTCode.computeIfAbsent(condition.tcodeKey(), k -> new ArrayList<>()).add(condition);
        }

        // OR across TCodes: user satisfies the group if ANY TCode is fully satisfied
        for (var entry : byTCode.entrySet()) {
            long tcodeKey = entry.getKey();
            List<SAPFunctionDef.AuthCondition> tcodeConditions = entry.getValue();

            // Does user have this TCode? (Star tcodes bypass this check — they match all accounts)
            if (!starTcodeKeys.contains(tcodeKey) && Arrays.binarySearch(user.resolvedEntitlements(), tcodeKey) < 0) {
                continue;  // doesn't have this TCode, try next
            }

            // AND within TCode: all auth conditions for this TCode must match
            boolean allAuthMatched = true;
            for (var condition : tcodeConditions) {
                if (!hasMatchingAuth(userAuthIndex, condition)) {
                    allAuthMatched = false;
                    break;
                }
            }

            if (allAuthMatched) {
                return true;  // This TCode + all its auth matched → group satisfied
            }
        }
        return false;  // No TCode fully satisfied
    }

    /**
     * Check if the user's auth index has a matching entry for this condition.
     * O(1) lookup by objectKey+fieldKey, then check value ranges.
     */
    private boolean hasMatchingAuth(Map<Long, List<AuthEntry>> userAuthIndex, SAPFunctionDef.AuthCondition condition) {
        long compositeKey = condition.objectKey() * 100000L + condition.fieldKey();
        List<AuthEntry> auths = userAuthIndex.get(compositeKey);
        if (auths == null || auths.isEmpty()) return false;

        for (AuthEntry auth : auths) {
            for (var range : condition.valueRanges()) {
                if (ValueMatcher.matches(range.minValue(), range.maxValue(), auth.minValue(), auth.maxValue(), range.absoluteValue(), considerPrecedingZeros)) {
                    return true;
                }
            }
        }
        return false;
    }
}
