package com.saviynt.sod.evaluation.agent;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

/**
 * DAO for agent SOD rule management (rulesets, functions, conditions, risks).
 * Writes to the same tables the evaluation engine reads from.
 */
@Repository
public class AgentRuleDao {

    private final JdbcTemplate jdbc;
    static final long AGENT_RULESET_START = 300;

    public AgentRuleDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // --- Rulesets ---

    public long createRuleset(String name, String description) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO rulesets (RULESET, DESCRIPTION, DEFAULTRULESET) VALUES (?, ?, 0)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, name);
            ps.setString(2, description);
            return ps;
        }, kh);
        return kh.getKey().longValue();
    }

    public List<Map<String, Object>> listRulesets() {
        return jdbc.queryForList("SELECT RULESETKEY, RULESET, DESCRIPTION FROM rulesets WHERE RULESETKEY >= ?", AGENT_RULESET_START);
    }

    public void updateRuleset(long key, String name, String description) {
        jdbc.update("UPDATE rulesets SET RULESET = ?, DESCRIPTION = ? WHERE RULESETKEY = ?", name, description, key);
    }

    // --- Functions ---

    public long createFunction(String name, String type, long rulesetKey) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO functions (FUNCTION_NAME, FUNCTIONTYPE, RULESETKEY, STATUS) VALUES (?, ?, ?, 0)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, name);
            ps.setString(2, type); // "SAP" or "NONSAP"
            ps.setLong(3, rulesetKey);
            return ps;
        }, kh);
        return kh.getKey().longValue();
    }

    public List<Map<String, Object>> listFunctions(long rulesetKey) {
        return jdbc.queryForList("SELECT FUNCTIONKEY, FUNCTION_NAME, FUNCTIONTYPE FROM functions WHERE RULESETKEY = ?", rulesetKey);
    }

    public void updateFunction(long key, String name, String type) {
        jdbc.update("UPDATE functions SET FUNCTION_NAME = ?, FUNCTIONTYPE = ? WHERE FUNCTIONKEY = ?", name, type, key);
    }

    public void deleteFunction(long key) {
        jdbc.update("DELETE FROM function_objects WHERE FUNCTIONKEY = ?", key);
        jdbc.update("DELETE FROM function_entitlements WHERE FUNCTIONKEY = ?", key);
        jdbc.update("UPDATE risks SET FUNCTION1KEY = NULL WHERE FUNCTION1KEY = ?", key);
        jdbc.update("UPDATE risks SET FUNCTION2KEY = NULL WHERE FUNCTION2KEY = ?", key);
        jdbc.update("UPDATE risks SET FUNCTION3KEY = NULL WHERE FUNCTION3KEY = ?", key);
        jdbc.update("UPDATE risks SET FUNCTION4KEY = NULL WHERE FUNCTION4KEY = ?", key);
        jdbc.update("UPDATE risks SET FUNCTION5KEY = NULL WHERE FUNCTION5KEY = ?", key);
        jdbc.update("DELETE FROM functions WHERE FUNCTIONKEY = ?", key);
    }

    // --- SAP Conditions (function_objects) ---

    public long addSapCondition(long functionKey, long groupKey, long tcodeKey, long objectKey, long fieldKey, String minValue, String maxValue) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO function_objects (FUNCTIONKEY, ENTITITLEMENT_VALUEKEY, OBJECTKEY, FIELDKEY, MINVALUE, MXVALUE, ENDPOINTKEY, FUNCTIONOBJECTGROUPKEY, STATUS) VALUES (?, ?, ?, ?, ?, ?, 0, ?, 0)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, functionKey);
            ps.setLong(2, tcodeKey);
            ps.setLong(3, objectKey);
            ps.setLong(4, fieldKey);
            ps.setString(5, minValue);
            ps.setString(6, maxValue);
            ps.setLong(7, groupKey);
            return ps;
        }, kh);
        return kh.getKey().longValue();
    }

    // --- NonSAP Conditions (function_entitlements) ---

    public void addNonSapCondition(long functionKey, long entitlementKey, int position, String prevOp, String nextOp) {
        jdbc.update("INSERT INTO function_entitlements (FUNCTIONKEY, ENTITLEMENT_VALUEKEY, CONDITIONPOSITION, PREVOPERATOR, NEXTOPERATOR, STATUS) VALUES (?, ?, ?, ?, ?, 0)",
                functionKey, entitlementKey, position, prevOp, nextOp);
    }

    // --- Generic condition listing/update/delete ---

    public List<Map<String, Object>> listConditions(long functionKey) {
        // Check function type to determine which table
        String type = jdbc.queryForObject("SELECT FUNCTIONTYPE FROM functions WHERE FUNCTIONKEY = ?", String.class, functionKey);
        if ("SAP".equalsIgnoreCase(type)) {
            return jdbc.queryForList("SELECT * FROM function_objects WHERE FUNCTIONKEY = ? AND STATUS = 0 ORDER BY FUNCTIONOBJECTGROUPKEY, OBJECTKEY", functionKey);
        } else {
            return jdbc.queryForList("SELECT * FROM function_entitlements WHERE FUNCTIONKEY = ? ORDER BY CONDITIONPOSITION", functionKey);
        }
    }

    public void deleteCondition(long functionKey, long conditionId, String type) {
        if ("SAP".equalsIgnoreCase(type)) {
            jdbc.update("DELETE FROM function_objects WHERE FUNCTIONKEY = ? AND FUNCTIONOBJECTKEY = ?", functionKey, conditionId);
        } else {
            jdbc.update("DELETE FROM function_entitlements WHERE FUNCTIONKEY = ? AND CONDITIONPOSITION = ?", functionKey, conditionId);
        }
    }

    // --- Risks ---

    public long createRisk(String name, long rulesetKey, List<Long> functionKeys) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO risks (RISKNAME, RULESETKEY, FUNCTION1KEY, FUNCTION2KEY, FUNCTION3KEY, FUNCTION4KEY, FUNCTION5KEY, STATUS, PRIORITY) VALUES (?, ?, ?, ?, ?, ?, ?, 0, 0)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, name);
            ps.setLong(2, rulesetKey);
            for (int i = 0; i < 5; i++) {
                if (i < functionKeys.size()) ps.setLong(3 + i, functionKeys.get(i));
                else ps.setNull(3 + i, java.sql.Types.BIGINT);
            }
            return ps;
        }, kh);
        return kh.getKey().longValue();
    }

    public List<Map<String, Object>> listRisks(long rulesetKey) {
        return jdbc.queryForList("SELECT RISKID, RISKNAME, FUNCTION1KEY, FUNCTION2KEY, FUNCTION3KEY, FUNCTION4KEY, FUNCTION5KEY FROM risks WHERE RULESETKEY = ? AND STATUS = 0", rulesetKey);
    }

    public void updateRisk(long riskId, String name, List<Long> functionKeys) {
        jdbc.update("UPDATE risks SET RISKNAME = ?, FUNCTION1KEY = ?, FUNCTION2KEY = ?, FUNCTION3KEY = ?, FUNCTION4KEY = ?, FUNCTION5KEY = ? WHERE RISKID = ?",
                name,
                functionKeys.size() > 0 ? functionKeys.get(0) : null,
                functionKeys.size() > 1 ? functionKeys.get(1) : null,
                functionKeys.size() > 2 ? functionKeys.get(2) : null,
                functionKeys.size() > 3 ? functionKeys.get(3) : null,
                functionKeys.size() > 4 ? functionKeys.get(4) : null,
                riskId);
    }

    public void deleteRisk(long riskId) {
        jdbc.update("UPDATE risks SET STATUS = 1 WHERE RISKID = ?", riskId);
    }
}
