package com.saviynt.sod.evaluation.dto;

import java.util.List;

/**
 * Request to trigger a detective SOD evaluation run.
 */
public record EvaluationRequest(
        List<Long> rulesetKeys,
        Long securitySystemId,
        String accountQuery,
        String entitlementQuery
) {
    public EvaluationRequest {
        if (rulesetKeys == null || rulesetKeys.isEmpty()) {
            rulesetKeys = List.of();  // will fall back to default rulesets
        }
    }
}
