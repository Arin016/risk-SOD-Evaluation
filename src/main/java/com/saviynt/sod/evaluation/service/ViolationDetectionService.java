package com.saviynt.sod.evaluation.service;

import com.saviynt.sod.evaluation.model.Risk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Detects violations by AND-ing function BitSets for each risk.
 *
 * For each risk, intersects the BitSets of its constituent functions.
 * Remaining set bits = violating users.
 *
 * Time complexity: O(risks × users/64) — sub-millisecond for typical workloads.
 */
@Service
public class ViolationDetectionService {

    private static final Logger log = LoggerFactory.getLogger(ViolationDetectionService.class);

    /**
     * Detect all violations across all risks.
     *
     * @param risks list of active risks to evaluate
     * @param functionBitSets map of "funcId###endpointKey" → BitSet
     * @param funcEndpointMap map of funcId → list of endpoint keys
     * @return map of "riskId###endpointKey" → BitSet of violating user indices
     */
    public Map<String, BitSet> detectViolations(List<Risk> risks,
                                                 Map<String, BitSet> functionBitSets,
                                                 Map<Long, List<Long>> funcEndpointMap) {
        Map<String, BitSet> violations = new HashMap<>();
        int totalViolators = 0;

        for (Risk risk : risks) {
            Set<Long> endpointSet = resolveEndpoints(risk, funcEndpointMap);

            for (long endpointKey : endpointSet) {
                BitSet result = intersectFunctionsForRisk(risk, endpointKey, functionBitSets);

                if (result != null && !result.isEmpty()) {
                    String key = risk.riskId() + "###" + endpointKey;
                    violations.put(key, result);
                    totalViolators += result.cardinality();
                }
            }
        }

        log.info("Violation detection complete: {} risks × endpoints produced {} total violation entries",
                violations.size(), totalViolators);
        return violations;
    }

    /**
     * Determine which endpoints to evaluate for a risk.
     * Mirrors current behavior: always includes 0, adds real endpoints from SAPGROUP functions.
     */
    private Set<Long> resolveEndpoints(Risk risk, Map<Long, List<Long>> funcEndpointMap) {
        Set<Long> endpoints = new HashSet<>();
        endpoints.add(0L);  // always include endpoint 0 (NonSAP + SAP plain)

        for (long funcKey : risk.functionKeys()) {
            List<Long> funcEndpoints = funcEndpointMap.get(funcKey);
            if (funcEndpoints != null) {
                endpoints.addAll(funcEndpoints);
            }
        }
        return endpoints;
    }

    /**
     * AND all function BitSets for a risk at a specific endpoint.
     * Returns null if any function has no BitSet at this endpoint (gotactual != totalfuninrisk).
     */
    private BitSet intersectFunctionsForRisk(Risk risk, long endpointKey, Map<String, BitSet> functionBitSets) {
        BitSet result = null;
        int matched = 0;

        for (long funcKey : risk.functionKeys()) {
            String key = funcKey + "###" + endpointKey;
            BitSet funcBits = functionBitSets.get(key);

            if (funcBits == null) {
                // This function has no evaluation at this endpoint — can't satisfy risk here
                continue;
            }

            matched++;
            if (result == null) {
                result = (BitSet) funcBits.clone();
            } else {
                result.and(funcBits);
                if (result.isEmpty()) return null;  // early exit — no users in all functions
            }
        }

        // Only return if ALL functions in the risk were matched (gotactual == totalfuninrisk)
        if (matched != risk.functionCount()) return null;
        return result;
    }
}
