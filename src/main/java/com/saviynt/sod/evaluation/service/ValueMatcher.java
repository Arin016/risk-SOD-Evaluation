package com.saviynt.sod.evaluation.service;

/**
 * SAP authorization value matching logic.
 * Handles wildcards (*) and range overlaps — exact port of violationFound() from ECMv4.
 *
 * Four cases:
 * 1. Both wildcard → match
 * 2. Rule wildcard, account specific → match
 * 3. Rule specific, account wildcard → match
 * 4. Neither wildcard → range overlap check
 */
public final class ValueMatcher {

    private static final String WILDCARD = "*";

    private ValueMatcher() {}

    /**
     * Does the account's auth value satisfy the function's required value?
     *
     * @param funcMin function's min value (what the rule requires)
     * @param funcMax function's max value
     * @param roleMin account's role min value (what the user has)
     * @param roleMax account's role max value
     * @return true if the values match (user has what the function requires)
     */
    public static boolean matches(String funcMin, String funcMax, String roleMin, String roleMax) {
        return matches(funcMin, funcMax, roleMin, roleMax, false);
    }

    public static boolean matches(String funcMin, String funcMax, String roleMin, String roleMax, boolean absoluteValue) {
        return matches(funcMin, funcMax, roleMin, roleMax, absoluteValue, false);
    }

    public static boolean matches(String funcMin, String funcMax, String roleMin, String roleMax, boolean absoluteValue, boolean considerPrecedingZeros) {
        if (funcMin == null || roleMin == null) return false;

        funcMin = normalize(funcMin);
        funcMax = normalize(funcMax);
        roleMin = normalize(roleMin);
        roleMax = normalize(roleMax);

        // If max is empty, treat as single value (max = min)
        if (funcMax.isEmpty()) funcMax = funcMin;
        if (roleMax.isEmpty()) roleMax = roleMin;

        // Strip leading zeros when considerPrecedingZeros is enabled
        if (considerPrecedingZeros) {
            funcMin = stripLeadingZeros(funcMin);
            funcMax = stripLeadingZeros(funcMax);
            roleMin = stripLeadingZeros(roleMin);
            roleMax = stripLeadingZeros(roleMax);
        }

        // Absolute value matching: strip quotes from function side only (matches old system behavior)
        if (absoluteValue) {
            funcMin = stripQuotes(funcMin);
            funcMax = stripQuotes(funcMax);
            // Role values keep their quotes — so exact match will fail unless role also has no quotes
            // This replicates the old system's behavior where absolute values rarely match
            return funcMin.equalsIgnoreCase(roleMin) || funcMin.equalsIgnoreCase(roleMax)
                    || funcMax.equalsIgnoreCase(roleMin) || funcMax.equalsIgnoreCase(roleMax);
        }

        // Case 1: Both wildcards
        if (isWildcard(funcMin) && isWildcard(roleMin)) return true;

        // Case 2: Function is wildcard — any account value satisfies
        if (isWildcard(funcMin)) return true;

        // Case 3: Account is wildcard — covers all values including the function's
        if (isWildcard(roleMin)) return true;

        // Case 4: Neither is wildcard — check range overlap
        // Old system always uses numeric comparison for all-digit values (Integer.parseInt in functionForOnlyDigits)
        // considerPrecedingZeros only controls leading zero stripping, not the comparison method
        return rangesOverlapNumeric(funcMin, funcMax, roleMin, roleMax);
    }

    private static String stripQuotes(String value) {
        if (value.startsWith("'") && value.endsWith("'") && value.length() > 2) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    /**
     * Range overlap check. Uses numeric comparison ONLY when considerPrecedingZeros is enabled.
     * Otherwise uses string comparison (matches old system's default behavior).
     */
    private static boolean rangesOverlap(String funcMin, String funcMax, String roleMin, String roleMax) {
        return (funcMin.compareTo(roleMin) <= 0 && roleMin.compareTo(funcMax) <= 0)
                || (funcMin.compareTo(roleMax) <= 0 && roleMax.compareTo(funcMax) <= 0)
                || (roleMin.compareTo(funcMin) <= 0 && funcMin.compareTo(roleMax) <= 0)
                || (roleMin.compareTo(funcMax) <= 0 && funcMax.compareTo(roleMax) <= 0);
    }

    private static boolean rangesOverlapNumeric(String funcMin, String funcMax, String roleMin, String roleMax) {
        if (isNumeric(funcMin) && isNumeric(funcMax) && isNumeric(roleMin) && isNumeric(roleMax)) {
            long fMin = Long.parseLong(funcMin);
            long fMax = Long.parseLong(funcMax);
            long rMin = Long.parseLong(roleMin);
            long rMax = Long.parseLong(roleMax);
            return (fMin <= rMin && rMin <= fMax)
                    || (fMin <= rMax && rMax <= fMax)
                    || (rMin <= fMin && fMin <= rMax)
                    || (rMin <= fMax && fMax <= rMax);
        }
        return rangesOverlap(funcMin, funcMax, roleMin, roleMax);
    }

    private static boolean isNumeric(String value) {
        if (value == null || value.isEmpty()) return false;
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) return false;
        }
        return true;
    }

    private static boolean isWildcard(String value) {
        return WILDCARD.equals(value) || value.startsWith("$");
    }

    private static String normalize(String value) {
        if (value == null) return "";
        value = value.trim();
        if (value.startsWith("$")) return WILDCARD;  // $BUKRS means wildcard
        return value;
    }

    private static String stripLeadingZeros(String value) {
        if (value.isEmpty() || WILDCARD.equals(value)) return value;
        // Only strip if the value is purely numeric
        if (!value.chars().allMatch(c -> Character.isDigit(c) || c == '-')) return value;
        String stripped = value.replaceFirst("^0+", "");
        return stripped.isEmpty() ? "0" : stripped;
    }

    /**
     * Check if a numeric range spans more than 1000 values.
     * Old system skips such broad ranges as they're too permissive.
     */
    public static boolean isRangeTooBroad(String minValue, String maxValue) {
        if (minValue == null || maxValue == null) return false;
        minValue = minValue.trim();
        maxValue = maxValue.trim();
        if (minValue.equals(maxValue)) return false;
        try {
            long min = Long.parseLong(minValue);
            long max = Long.parseLong(maxValue);
            return (max - min) > 1000;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
