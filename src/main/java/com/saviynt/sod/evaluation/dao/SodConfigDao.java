package com.saviynt.sod.evaluation.dao;

import com.saviynt.sod.evaluation.model.*;
import com.saviynt.sod.evaluation.model.NonSAPCondition.FunctionEntitlementRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Data access for SOD configuration: rulesets, risks, functions, function_objects, function_entitlements.
 */
@Repository
public class SodConfigDao {

    private static final Logger log = LoggerFactory.getLogger(SodConfigDao.class);
    private final JdbcTemplate jdbc;

    public SodConfigDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Load default ruleset keys (where DEFAULTRULESET = 1).
     */
    public List<Long> loadDefaultRulesetKeys() {
        return jdbc.queryForList(
                "SELECT RULESETKEY FROM rulesets WHERE DEFAULTRULESET = 1",
                Long.class);
    }

    /**
     * Load all active risks for the given rulesets.
     */
    public List<Risk> loadActiveRisks(List<Long> rulesetKeys) {
        String placeholders = rulesetKeys.stream().map(k -> "?").collect(Collectors.joining(","));
        String sql = """
                SELECT RISKID, RISKNAME, RULESETKEY, FUNCTION1KEY, FUNCTION2KEY,
                       FUNCTION3KEY, FUNCTION4KEY, FUNCTION5KEY
                FROM risks
                WHERE STATUS = 0 AND FUNCTION1KEY IS NOT NULL AND RULESETKEY IN (%s)
                """.formatted(placeholders);

        return jdbc.query(sql, rulesetKeys.toArray(), (rs, rowNum) -> {
            List<Long> funcKeys = new ArrayList<>(5);
            for (int i = 4; i <= 8; i++) {
                long fk = rs.getLong(i);
                if (!rs.wasNull()) funcKeys.add(fk);
            }
            return new Risk(rs.getLong(1), rs.getString(2), rs.getLong(3), funcKeys);
        });
    }

    /**
     * Load function definitions.
     */
    public Map<Long, SodFunction> loadFunctions(Set<Long> functionKeys) {
        if (functionKeys.isEmpty()) return Map.of();
        String placeholders = functionKeys.stream().map(k -> "?").collect(Collectors.joining(","));
        String sql = "SELECT FUNCTIONKEY, FUNCTION_NAME, FUNCTIONTYPE, EXCLUSIONQRY FROM functions WHERE FUNCTIONKEY IN (%s)"
                .formatted(placeholders);

        Map<Long, SodFunction> result = new HashMap<>();
        jdbc.query(sql, functionKeys.toArray(), (rs, rowNum) -> {
            long key = rs.getLong(1);
            result.put(key, new SodFunction(key, rs.getString(2),
                    SodFunction.FunctionType.from(rs.getString(3)), rs.getString(4)));
            return null;
        });
        return result;
    }

    /**
     * Load NonSAP function conditions (FUNCTION_ENTITLEMENTS).
     * Returns: funcKey → compiled NonSAPCondition.
     */
    public Map<Long, NonSAPCondition> loadNonSAPConditions(Set<Long> functionKeys) {
        if (functionKeys.isEmpty()) return Map.of();
        String placeholders = functionKeys.stream().map(k -> "?").collect(Collectors.joining(","));
        String sql = """
                SELECT FUNCTIONKEY, ENTITLEMENT_VALUEKEY, CONDITIONPOSITION, PREVOPERATOR, NEXTOPERATOR
                FROM function_entitlements
                WHERE FUNCTIONKEY IN (%s)
                ORDER BY FUNCTIONKEY, CONDITIONPOSITION
                """.formatted(placeholders);

        Map<Long, List<FunctionEntitlementRow>> rowsByFunc = new HashMap<>();

        jdbc.query(sql, functionKeys.toArray(), (rs, rowNum) -> {
            long funcKey = rs.getLong(1);
            rowsByFunc.computeIfAbsent(funcKey, k -> new ArrayList<>())
                    .add(new FunctionEntitlementRow(
                            rs.getLong(2), rs.getInt(3), rs.getString(4), rs.getString(5)));
            return null;
        });

        Map<Long, NonSAPCondition> conditions = new HashMap<>();
        rowsByFunc.forEach((funcKey, rows) -> conditions.put(funcKey, NonSAPCondition.compile(rows)));
        return conditions;
    }

    /**
     * Load SAP function definitions (FUNCTION_OBJECTS).
     * Groups by: function → endpoint → functionObjectGroupKey → TCode+Object+Field.
     * Multiple values for same TCode+Object+Field are OR'd (alternatives).
     * Different TCode+Object+Field combos within a group are AND'd.
     */
    public Map<Long, SAPFunctionDef> loadSAPFunctionDefs(Set<Long> functionKeys) {
        if (functionKeys.isEmpty()) return Map.of();
        String placeholders = functionKeys.stream().map(k -> "?").collect(Collectors.joining(","));
        String sql = """
                SELECT FUNCTIONKEY, ENTITITLEMENT_VALUEKEY, OBJECTKEY, FIELDKEY,
                       MINVALUE, MXVALUE, ENDPOINTKEY, FUNCTIONOBJECTGROUPKEY, RELATION
                FROM function_objects
                WHERE STATUS = 0 AND FUNCTIONKEY IN (%s)
                ORDER BY FUNCTIONKEY, ENDPOINTKEY, FUNCTIONOBJECTGROUPKEY, ENTITITLEMENT_VALUEKEY, OBJECTKEY, FIELDKEY
                """.formatted(placeholders);

        // Raw row collection: func → endpoint → group → "tcode#obj#field" → list of value ranges
        record RawRow(long funcKey, long endpointKey, long groupKey, long tcodeKey, long objectKey, long fieldKey, String minVal, String maxVal, String relation) {}
        List<RawRow> rows = new ArrayList<>();

        jdbc.query(sql, functionKeys.toArray(), (rs, rowNum) -> {
            long funcKey = rs.getLong(1);
            long tcodeKey = rs.getLong(2);
            long objectKey = rs.getLong(3);
            long fieldKey = rs.getLong(4);
            String minVal = rs.getString(5);
            String maxVal = rs.getString(6);
            long endpointKey = rs.getLong(7);
            if (rs.wasNull()) endpointKey = 0;
            long groupKey = rs.getLong(8);
            if (rs.wasNull()) groupKey = -1;
            String relation = rs.getString(9);

            rows.add(new RawRow(funcKey, endpointKey, groupKey, tcodeKey, objectKey, fieldKey, minVal, maxVal, relation));
            return null;
        });

        // Build structured SAPFunctionDefs
        // Group: func → endpoint → groupKey → "tcode#obj#field" → List<ValueRange>
        Map<Long, Map<Long, Map<Long, Map<String, List<SAPFunctionDef.ValueRange>>>>> nested = new HashMap<>();

        for (RawRow row : rows) {
            boolean absValue = row.minVal() != null && row.minVal().startsWith("'");
            String condKey = row.tcodeKey() + "#" + row.objectKey() + "#" + row.fieldKey();

            nested.computeIfAbsent(row.funcKey(), k -> new HashMap<>())
                    .computeIfAbsent(row.endpointKey(), k -> new HashMap<>())
                    .computeIfAbsent(row.groupKey(), k -> new LinkedHashMap<>())
                    .computeIfAbsent(condKey, k -> new ArrayList<>())
                    .add(new SAPFunctionDef.ValueRange(row.minVal(), row.maxVal(), absValue));
        }

        // Convert to SAPFunctionDef records
        Map<Long, SAPFunctionDef> result = new HashMap<>();
        nested.forEach((funcKey, byEndpoint) -> {
            List<Long> endpoints = new ArrayList<>(byEndpoint.keySet());
            Map<Long, List<SAPFunctionDef.AuthConditionGroup>> condsByEp = new HashMap<>();

            byEndpoint.forEach((ep, byGroup) -> {
                List<SAPFunctionDef.AuthConditionGroup> groups = new ArrayList<>();
                byGroup.forEach((groupKey, conditionsMap) -> {
                    List<SAPFunctionDef.AuthCondition> conditions = new ArrayList<>();
                    conditionsMap.forEach((condKey, valueRanges) -> {
                        String[] parts = condKey.split("#");
                        conditions.add(new SAPFunctionDef.AuthCondition(
                                Long.parseLong(parts[0]), Long.parseLong(parts[1]),
                                Long.parseLong(parts[2]), valueRanges));
                    });
                    groups.add(new SAPFunctionDef.AuthConditionGroup(
                            groupKey == -1 ? null : groupKey, conditions));
                });
                condsByEp.put(ep, groups);
            });

            result.put(funcKey, new SAPFunctionDef(funcKey, endpoints, condsByEp));
        });

        return result;
    }

    /**
     * Run a function's exclusionQry to get excluded entitlement keys.
     * Returns empty set if query is null/blank or fails.
     */
    public Set<Long> loadFunctionExcludedEnts(String exclusionQry) {
        if (exclusionQry == null || exclusionQry.isBlank()) return Set.of();
        try {
            return new HashSet<>(jdbc.queryForList(exclusionQry, Long.class));
        } catch (Exception e) {
            log.warn("Failed to run exclusionQry: {}", e.getMessage());
            return Set.of();
        }
    }

    /** Load considerPrecedingZeros config from CONFIGURATION table. */
    public boolean loadConsiderPrecedingZeros() {
        try {
            String val = jdbc.queryForObject(
                    "SELECT CONFIGDATA FROM configuration WHERE CONFIGKEY = 'sod.fieldval.considerPrecedingZeros'", String.class);
            return "true".equalsIgnoreCase(val) || "1".equals(val);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Load excluded entitlement pairs for NonSAP evaluation.
     * Old system: excludedEntSetNONSAP = {"parentKey#PROGRAM"} where parent is from entitlements2
     * and child has type 'Excluded%' with PROGRAM IS NOT NULL.
     * isExcludedByEntitlementType checks: row.PARENTENT#row.IMMEDIATECHILDENT against this set.
     * Match when IMMEDIATECHILDENT (Long key as string) == PROGRAM value.
     *
     * For our system: returns Set of "parentKey#childKey" where childKey is the excluded node's key
     * (since PROGRAM is set to the child's own key in proper setups).
     * We check: if user's direct assignment has an immediate child that's an excluded node.
     */
    public Set<String> loadExcludedEntPairs() {
        String sql = """
                SELECT E2.ENTITLEMENT_VALUE1KEY AS PARENTENT, EV.PROGRAM AS CHILDENT
                FROM entitlements2 E2
                JOIN entitlement_values EV ON E2.ENTITLEMENT_VALUE2KEY = EV.ENTITLEMENT_VALUEKEY
                JOIN entitlement_types ET ON EV.ENTITLEMENTTYPEKEY = ET.ENTITLEMENTTYPEKEY
                WHERE ET.ENTITLEMENTNAME LIKE 'Excluded%'
                AND EV.PROGRAM IS NOT NULL
                """;
        Set<String> result = new HashSet<>();
        try {
            jdbc.query(sql, rs -> {
                result.add(rs.getLong(1) + "#" + rs.getString(2));
            });
            if (!result.isEmpty()) log.info("Loaded {} excluded ent pairs (NonSAP entitlement type exclusion)", result.size());
        } catch (Exception e) {
            log.warn("Failed to load excluded ent pairs: {}", e.getMessage());
        }
        return result;
    }

    /**
     * Load star tcode keys — entitlement_values where ENTITLEMENT_VALUE = '*' and type = 'tcode'.
     * These tcodes match ALL accounts (tcode ownership check is bypassed).
     */

    /**
     * Load resolved TCD-field tcodes per function.
     * For function_objects with fieldkey=65 (TCD), resolves MINVALUE to actual tcode keys.
     * Returns: funcKey → Map<groupTcodeKey, Set<resolvedTcodeKey>>
     */
    public Map<Long, Map<Long, Set<Long>>> loadTcdFieldResolvedTcodes(Set<Long> functionKeys, Long securitySystemId) {
        if (functionKeys.isEmpty()) return Map.of();
        // First load tcodeValueKeyMap: tcode_value_string → entitlement_valuekey
        String tkvSql = """
                SELECT ev.ENTITLEMENT_VALUE, ev.ENTITLEMENT_VALUEKEY
                FROM entitlement_values ev
                JOIN entitlement_types et ON ev.ENTITLEMENTTYPEKEY = et.ENTITLEMENTTYPEKEY
                WHERE et.ENTITLEMENTNAME = 'tcode'
                """ + (securitySystemId != null ? " AND et.ENDPOINTKEY IN (SELECT ENDPOINTKEY FROM endpoints WHERE SECURITYSYSTEMKEY = " + securitySystemId + ")" : "");
        Map<String, Long> tcodeValueKeyMap = new HashMap<>();
        jdbc.query(tkvSql, rs -> { tcodeValueKeyMap.put(rs.getString(1), rs.getLong(2)); });
        log.info("Loaded tcodeValueKeyMap: {} entries", tcodeValueKeyMap.size());

        // Now load TCD-field entries from function_objects (fieldkey=65)
        String placeholders = functionKeys.stream().map(k -> "?").collect(Collectors.joining(","));
        String sql = "SELECT FUNCTIONKEY, ENTITITLEMENT_VALUEKEY, MINVALUE FROM function_objects WHERE STATUS=0 AND FIELDKEY=65 AND FUNCTIONKEY IN (" + placeholders + ")";
        Map<Long, Map<Long, Set<Long>>> result = new HashMap<>();
        jdbc.query(sql, functionKeys.toArray(), (rs, rowNum) -> {
            long funcKey = rs.getLong(1);
            long groupTcode = rs.getLong(2);
            String minValue = rs.getString(3);
            Long resolvedKey = tcodeValueKeyMap.get(minValue);
            if (resolvedKey != null && resolvedKey != groupTcode) {
                result.computeIfAbsent(funcKey, k -> new HashMap<>())
                      .computeIfAbsent(groupTcode, k -> new HashSet<>())
                      .add(resolvedKey);
            }
            return null;
        });
        log.info("Loaded TCD-field resolved tcodes for {} functions", result.size());
        return result;
    }


    /**
     * Load tcodeRoleMap equivalent: set of tcode keys that have at least one direct-assignment role as parent.
     * Replicates old system's getTcodeRoleMap query: finds tcodes where account_entitlements1.role → entitlements2 → tcode.
     * Only tcodes in this set get detail rows in the old system.
     */
    public Set<Long> loadTcodesWithDirectRoleParent(Long securitySystemId) {
        String sql = """
                SELECT DISTINCT e2.ENTITLEMENT_VALUE2KEY
                FROM account_entitlements1 ae
                JOIN accounts a ON ae.ACCOUNTKEY = a.ACCOUNTKEY
                JOIN entitlements2 e2 ON ae.ENTITLEMENT_VALUEKEY = e2.ENTITLEMENT_VALUE1KEY
                WHERE a.STATUS <> 'SUSPENDED FROM IMPORT SERVICE'
                """ + (securitySystemId != null ? " AND a.SYSTEMID = " + securitySystemId : "");
        Set<Long> result = new HashSet<>(jdbc.queryForList(sql, Long.class));
        log.info("Loaded tcodeRoleMap equivalent: {} tcodes with direct-role parents", result.size());
        return result;
    }

    public Set<Long> loadStarTcodeKeys(Long securitySystemId) {
        String sql = """
                SELECT ev.ENTITLEMENT_VALUEKEY
                FROM entitlement_values ev
                JOIN entitlement_types et ON ev.ENTITLEMENTTYPEKEY = et.ENTITLEMENTTYPEKEY
                JOIN endpoints ep ON et.ENDPOINTKEY = ep.ENDPOINTKEY
                WHERE ev.ENTITLEMENT_VALUE = '*'
                AND et.ENTITLEMENTNAME = 'tcode'
                """ + (securitySystemId != null ? " AND ep.SECURITYSYSTEMKEY = " + securitySystemId : "");
        Set<Long> starKeys = new HashSet<>(jdbc.queryForList(sql, Long.class));
        log.info("Loaded {} star tcode keys", starKeys.size());
        return starKeys;
    }

    /**
     * Load existing open violation keys for closure detection.
     * Returns: Set of "userIdentifier#riskId#endpointKey" strings.
     */
    public Map<String, Long> loadExistingViolationKeys(List<Long> rulesetKeys) {
        String placeholders = rulesetKeys.stream().map(k -> "?").collect(Collectors.joining(","));
        String sql = """
                SELECT s.SODKEY, s.USERIDENTIFIER, s.RISKKEY, s.ENDPOINTKEY
                FROM sodrisks s
                JOIN risks r ON s.RISKKEY = r.RISKID
                WHERE r.RULESETKEY IN (%s) AND s.STATUS IN (1, 2, 3)
                """.formatted(placeholders);

        Map<String, Long> existing = new HashMap<>();
        jdbc.query(sql, rulesetKeys.toArray(), (rs, rowNum) -> {
            long sodKey = rs.getLong(1);
            long userIdentifier = rs.getLong(2);
            long riskKey = rs.getLong(3);
            long endpointKey = rs.getLong(4);
            existing.put(userIdentifier + "#" + riskKey + "#" + endpointKey, sodKey);
            return null;
        });

        log.info("Loaded {} existing open violations", existing.size());
        return existing;
    }
}
