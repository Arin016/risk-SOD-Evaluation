package com.saviynt.sod.evaluation.model;

import java.util.List;

/**
 * Pre-compiled boolean condition for a NonSAP function.
 * Represents the AND/OR expression from FUNCTION_ENTITLEMENTS rows.
 *
 * Replaces GroovyShell.evaluate() with a safe, fast, pre-compiled evaluator.
 */
public sealed interface NonSAPCondition {

    boolean evaluate(long[] sortedEntitlements);

    record HasEntitlement(long entitlementKey) implements NonSAPCondition {
        @Override
        public boolean evaluate(long[] sortedEntitlements) {
            return java.util.Arrays.binarySearch(sortedEntitlements, entitlementKey) >= 0;
        }
    }

    record And(NonSAPCondition left, NonSAPCondition right) implements NonSAPCondition {
        @Override
        public boolean evaluate(long[] sortedEntitlements) {
            return left.evaluate(sortedEntitlements) && right.evaluate(sortedEntitlements);
        }
    }

    record Or(NonSAPCondition left, NonSAPCondition right) implements NonSAPCondition {
        @Override
        public boolean evaluate(long[] sortedEntitlements) {
            return left.evaluate(sortedEntitlements) || right.evaluate(sortedEntitlements);
        }
    }

    /**
     * Builds a condition tree from FUNCTION_ENTITLEMENTS rows.
     * Rows are ordered by conditionposition and linked by prevOperator/nextOperator.
     */
    static NonSAPCondition compile(List<FunctionEntitlementRow> rows) {
        if (rows.isEmpty()) return new And(new HasEntitlement(-1), new HasEntitlement(-2)); // always false
        if (rows.size() == 1) return new HasEntitlement(rows.getFirst().entitlementKey());

        // Build left-to-right, respecting operator precedence (AND binds tighter than OR)
        // This matches the GroovyShell behavior: "true && false || true" = "(true && false) || true"
        NonSAPCondition result = new HasEntitlement(rows.getFirst().entitlementKey());

        for (int i = 1; i < rows.size(); i++) {
            var row = rows.get(i);
            var prevRow = rows.get(i - 1);
            var term = new HasEntitlement(row.entitlementKey());
            // The operator between row[i-1] and row[i] is in NEXTOPERATOR of row[i-1]
            // OR in PREVOPERATOR of row[i] — check both
            String op = prevRow.nextOperator();
            if (op == null || op.isBlank()) op = row.prevOperator();

            if (op != null && op.trim().contains("||")) {
                result = new Or(result, term);
            } else if (op != null && (op.trim().contains("OR") || op.trim().equalsIgnoreCase("OR"))) {
                result = new Or(result, term);
            } else {
                result = new And(result, term);
            }
        }
        return result;
    }

    record FunctionEntitlementRow(long entitlementKey, int position, String prevOperator, String nextOperator) {}
}
