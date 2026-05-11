package com.saviynt.sod.evaluation.model;

/**
 * Represents a SOD Function — a business capability defined as a predicate over entitlements.
 */
public record SodFunction(
        long functionKey,
        String functionName,
        FunctionType type,
        String exclusionQuery
) {
    public enum FunctionType {
        SAP,
        NONSAP,
        SAPGROUP;

        public static FunctionType from(String dbValue) {
            if (dbValue == null) return NONSAP;  // null defaults to NonSAP (matches current behavior)
            return switch (dbValue.trim().toUpperCase().replace("'", "")) {
                case "SAP" -> SAP;
                case "SAPGROUP" -> SAPGROUP;
                default -> NONSAP;
            };
        }
    }
}
