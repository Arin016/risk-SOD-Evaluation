package com.saviynt.sod.evaluation.model;

import java.util.List;
import java.util.Map;

/**
 * Pre-loaded SAP function definition.
 * Groups of auth conditions: AND within a group, OR across groups.
 */
public record SAPFunctionDef(
        long functionKey,
        List<Long> endpoints,                    // for SAPGROUP: [9, 10]; for SAP plain: [0]
        Map<Long, List<AuthConditionGroup>> conditionsByEndpoint  // endpointKey → groups
) {
    /**
     * A group of auth conditions that must ALL be satisfied (AND within group).
     * Multiple groups are OR'd — satisfying any one group is sufficient.
     */
    public record AuthConditionGroup(
            Long groupKey,  // functionObjectGroupKey (null = default group)
            List<AuthCondition> conditions
    ) {}

    /**
     * A single auth condition: TCode + AuthObject + Field + list of value ranges (OR'd).
     * Multiple values for the same TCode+Object+Field are alternatives (OR).
     * The user satisfies this condition if their auth matches ANY of the value ranges.
     */
    public record AuthCondition(
            long tcodeKey,
            long objectKey,
            long fieldKey,
            List<ValueRange> valueRanges  // OR'd — match ANY range
    ) {}

    public record ValueRange(String minValue, String maxValue, boolean absoluteValue) {}
}
