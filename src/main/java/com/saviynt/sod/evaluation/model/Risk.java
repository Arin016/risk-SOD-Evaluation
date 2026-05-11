package com.saviynt.sod.evaluation.model;

import java.util.List;

/**
 * Immutable representation of a SOD Risk — a forbidden combination of functions.
 */
public record Risk(
        long riskId,
        String riskName,
        long rulesetKey,
        List<Long> functionKeys  // 2-5 function keys (non-null ones from function1key..function5key)
) {
    public int functionCount() {
        return functionKeys.size();
    }
}
