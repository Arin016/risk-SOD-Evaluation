package com.saviynt.sod.evaluation.model;

import java.util.List;

/**
 * A detected SOD violation — one user satisfying all functions in a risk.
 */
public record Violation(
        long userKey,
        long userIdentifier,
        long riskId,
        long endpointKey,
        List<ViolationEvidence> evidence
) {
    /**
     * Evidence for one function match within a violation.
     */
    public record ViolationEvidence(
            long accountKey,
            long entitlementKey,
            long associatedRoleKey,
            long functionKey,
            String parentRoleKeysCSV,
            String parentRoleValuesCSV,
            // SAP-specific (null for NonSAP)
            Long objectKey,
            Long fieldKey,
            String fieldValue,
            Long tcodeKey
    ) {
        public boolean isSAP() {
            return objectKey != null;
        }
    }
}
