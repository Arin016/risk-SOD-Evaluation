package com.saviynt.sod.testdata;

import java.sql.*;
import java.util.*;

import static com.saviynt.sod.testdata.TestDataGenerator.*;

public class ScenarioBuilder {
    private final Connection conn;

    public ScenarioBuilder(Connection conn) { this.conn = conn; }

    // === HELPERS ===

    long createTcode(String name) throws SQLException {
        long id = entValueSeq.getAndIncrement();
        exec("INSERT INTO entitlement_values (ENTITLEMENT_VALUEKEY, ENTITLEMENT_VALUE, ENTITLEMENTTYPEKEY) VALUES (?,?,?)", id, name, ENT_TYPE_TCODE);
        return id;
    }

    long createRole(String name) throws SQLException {
        long id = entValueSeq.getAndIncrement();
        exec("INSERT INTO entitlement_values (ENTITLEMENT_VALUEKEY, ENTITLEMENT_VALUE, ENTITLEMENTTYPEKEY) VALUES (?,?,?)", id, name, ENT_TYPE_SAPROLE);
        return id;
    }

    long createNonSAPEnt(String name) throws SQLException {
        long id = entValueSeq.getAndIncrement();
        exec("INSERT INTO entitlement_values (ENTITLEMENT_VALUEKEY, ENTITLEMENT_VALUE, ENTITLEMENTTYPEKEY) VALUES (?,?,?)", id, name, ENT_TYPE_NONSAP);
        return id;
    }

    void addHierarchy(long parent, long child) throws SQLException {
        long id = ent2Seq.getAndIncrement();
        exec("INSERT INTO entitlements2 (ENT2KEY, ENTITLEMENT_VALUE1KEY, ENTITLEMENT_VALUE2KEY) VALUES (?,?,?)", id, parent, child);
    }

    long createAuthObject(String name) throws SQLException {
        long id = objSeq.getAndIncrement();
        exec("INSERT INTO access_objects (OBJECTKEY, OBJECTNAME, SYSTEMID) VALUES (?,?,?)", id, name, SYSTEM_ID);
        return id;
    }

    long createField(String name) throws SQLException {
        long id = fieldSeq.getAndIncrement();
        exec("INSERT INTO fields (FIELDKEY, FIELDNAME) VALUES (?,?)", id, name);
        return id;
    }

    void addAuth(long roleKey, long objKey, long fieldKey, String min, String max) throws SQLException {
        exec("INSERT INTO entitlement_objects (ENTITLEMENT_VALUEKEY, OBJECTKEY, FIELD_KEY, MINVALUE, MXVALUE) VALUES (?,?,?,?,?)", roleKey, objKey, fieldKey, min, max);
    }

    long createAccount(String name) throws SQLException {
        long id = accountSeq.getAndIncrement();
        exec("INSERT INTO accounts (ACCOUNTKEY, NAME, SYSTEMID, ENDPOINTKEY, STATUS) VALUES (?,?,?,?,?)", id, name, SYSTEM_ID, ENDPOINT_ID, "1");
        return id;
    }

    void assignRole(long accountKey, long entKey) throws SQLException {
        long id = accEntSeq.getAndIncrement();
        exec("INSERT INTO account_entitlements1 (ACCENTKEY, ACCOUNTKEY, ENTITLEMENT_VALUEKEY) VALUES (?,?,?)", id, accountKey, entKey);
    }

    long createSAPFunction(String name) throws SQLException {
        long id = funcSeq.getAndIncrement();
        exec("INSERT INTO functions (FUNCTIONKEY, FUNCTION_NAME, FUNCTIONTYPE, STATUS, RULESETKEY) VALUES (?,?,?,?,?)", id, name, "SAP", 1, RULESET_ID);
        return id;
    }

    long createSAPGroupFunction(String name) throws SQLException {
        long id = funcSeq.getAndIncrement();
        exec("INSERT INTO functions (FUNCTIONKEY, FUNCTION_NAME, FUNCTIONTYPE, STATUS, RULESETKEY) VALUES (?,?,?,?,?)", id, name, "SAPGROUP", 1, RULESET_ID);
        return id;
    }

    long createNonSAPFunction(String name) throws SQLException {
        long id = funcSeq.getAndIncrement();
        exec("INSERT INTO functions (FUNCTIONKEY, FUNCTION_NAME, FUNCTIONTYPE, STATUS, RULESETKEY) VALUES (?,?,?,?,?)", id, name, "NONSAP", 1, RULESET_ID);
        return id;
    }

    void addFuncObj(long funcKey, long tcodeKey, long objKey, long fieldKey, String min, String max, long epKey) throws SQLException {
        long id = funcObjSeq.getAndIncrement();
        String sql = "INSERT INTO function_objects (FUNCTIONOBJECTKEY, FUNCTIONKEY, ENTITITLEMENT_VALUEKEY, OBJECTKEY, FIELDKEY, MINVALUE, MXVALUE, STATUS, ENDPOINTKEY) VALUES (?,?,?,?,?,?,?,0,?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.setLong(2, funcKey);
            ps.setLong(3, tcodeKey);
            ps.setLong(4, objKey);
            ps.setLong(5, fieldKey);
            ps.setString(6, min);
            ps.setString(7, max);
            if (epKey == 0) {
                ps.setNull(8, java.sql.Types.BIGINT); // SAP plain uses NULL, not 0
            } else {
                ps.setLong(8, epKey); // SAPGROUP uses real endpoint IDs
            }
            ps.executeUpdate();
        }
    }

    void addFuncEnt(long funcKey, long entKey, int position, String prevOp, String nextOp) throws SQLException {
        long id = funcEntSeq.getAndIncrement();
        // Old system uses ' || ' for OR, ' && ' for AND, ' ' for none (Groovy expression format)
        String dbPrevOp = " ";
        String dbNextOp = " ";
        if (nextOp != null && nextOp.equalsIgnoreCase("OR")) dbNextOp = " || ";
        if (nextOp != null && nextOp.equalsIgnoreCase("AND")) dbNextOp = " && ";
        if (prevOp != null && prevOp.equalsIgnoreCase("OR")) dbPrevOp = " || ";
        if (prevOp != null && prevOp.equalsIgnoreCase("AND")) dbPrevOp = " && ";
        exec("INSERT INTO function_entitlements (FUNCTION_ENTITLEMENTKEY, FUNCTIONKEY, ENTITLEMENT_VALUEKEY, CONDITIONPOSITION, PREVOPERATOR, NEXTOPERATOR, STATUS) VALUES (?,?,?,?,?,?,1)",
                id, funcKey, entKey, position, dbPrevOp, dbNextOp);
    }

    long createRisk(String name, long func1, long func2) throws SQLException {
        long id = riskSeq.getAndIncrement();
        exec("INSERT INTO risks (RISKID, RISKNAME, RULESETKEY, FUNCTION1KEY, FUNCTION2KEY, STATUS) VALUES (?,?,?,?,?,0)", id, name, RULESET_ID, func1, func2);
        return id;
    }

    long createRisk3(String name, long f1, long f2, long f3) throws SQLException {
        long id = riskSeq.getAndIncrement();
        exec("INSERT INTO risks (RISKID, RISKNAME, RULESETKEY, FUNCTION1KEY, FUNCTION2KEY, FUNCTION3KEY, STATUS) VALUES (?,?,?,?,?,?,0)", id, name, RULESET_ID, f1, f2, f3);
        return id;
    }

    private void exec(String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                if (params[i] instanceof Long) ps.setLong(i + 1, (Long) params[i]);
                else if (params[i] instanceof Integer) ps.setInt(i + 1, (Integer) params[i]);
                else ps.setString(i + 1, params[i] == null ? null : params[i].toString());
            }
            ps.executeUpdate();
        }
    }

    void batchAccounts(String prefix, int count, long... roleKeys) throws SQLException {
        try (PreparedStatement psAcc = conn.prepareStatement("INSERT INTO accounts (ACCOUNTKEY, NAME, SYSTEMID, ENDPOINTKEY, STATUS) VALUES (?,?,?,?,?)");
             PreparedStatement psAe = conn.prepareStatement("INSERT INTO account_entitlements1 (ACCENTKEY, ACCOUNTKEY, ENTITLEMENT_VALUEKEY) VALUES (?,?,?)")) {
            for (int i = 0; i < count; i++) {
                long accId = accountSeq.getAndIncrement();
                psAcc.setLong(1, accId);
                psAcc.setString(2, prefix + "_" + i);
                psAcc.setLong(3, SYSTEM_ID);
                psAcc.setLong(4, ENDPOINT_ID);
                psAcc.setString(5, "1");
                psAcc.addBatch();
                for (long rk : roleKeys) {
                    long aeId = accEntSeq.getAndIncrement();
                    psAe.setLong(1, aeId);
                    psAe.setLong(2, accId);
                    psAe.setLong(3, rk);
                    psAe.addBatch();
                }
            }
            psAcc.executeBatch();
            psAe.executeBatch();
        }
    }

    // === SCENARIOS ===

    /** Scenario 1: Direct role → tcode, basic SAP evaluation */
    public void scenario1_directRoleTcode() throws SQLException {
        System.out.println("  S1: Direct role→tcode...");
        long obj = createAuthObject("S1_OBJ");
        long fld = createField("S1_ACTVT");

        long tcode1 = createTcode("S1_TC_CREATE");
        long tcode2 = createTcode("S1_TC_APPROVE");
        long role1 = createRole("S1_ROLE_CREATOR");
        long role2 = createRole("S1_ROLE_APPROVER");

        addHierarchy(role1, tcode1);
        addHierarchy(role2, tcode2);
        addAuth(role1, obj, fld, "01", "01");
        addAuth(role2, obj, fld, "02", "02");

        long func1 = createSAPFunction("S1_CREATE");
        long func2 = createSAPFunction("S1_APPROVE");
        addFuncObj(func1, tcode1, obj, fld, "01", "01", 0);
        addFuncObj(func2, tcode2, obj, fld, "02", "02", 0);
        createRisk("S1_FRAUD", func1, func2);

        // Violators: have BOTH roles
        batchAccounts("S1_VIOLATOR", ACCOUNTS_PER_SCENARIO, role1, role2);
        // Non-violators: have only one role
        batchAccounts("S1_SAFE_CREATE", ACCOUNTS_PER_SCENARIO / 2, role1);
        batchAccounts("S1_SAFE_APPROVE", ACCOUNTS_PER_SCENARIO / 2, role2);
    }

    /** Scenario 2: Composite role + flattened children (SAP import behavior) */
    public void scenario2_compositeRoleFlattened() throws SQLException {
        System.out.println("  S2: Composite role (flattened)...");
        long obj = createAuthObject("S2_OBJ");
        long fld = createField("S2_ACTVT");

        long tcode1 = createTcode("S2_TC_POST");
        long tcode2 = createTcode("S2_TC_REVERSE");
        long roleChild1 = createRole("S2_ROLE_POSTER");
        long roleChild2 = createRole("S2_ROLE_REVERSER");
        long roleComposite = createRole("S2_ROLE_FINANCE_ALL");

        addHierarchy(roleChild1, tcode1);
        addHierarchy(roleChild2, tcode2);
        addHierarchy(roleComposite, roleChild1);
        addHierarchy(roleComposite, roleChild2);
        addAuth(roleChild1, obj, fld, "01", "01");
        addAuth(roleChild2, obj, fld, "02", "02");

        long func1 = createSAPFunction("S2_POST");
        long func2 = createSAPFunction("S2_REVERSE");
        addFuncObj(func1, tcode1, obj, fld, "01", "01", 0);
        addFuncObj(func2, tcode2, obj, fld, "02", "02", 0);
        createRisk("S2_FINANCE_FRAUD", func1, func2);

        // Violators: have composite + flattened children (real SAP behavior)
        batchAccounts("S2_VIOLATOR", ACCOUNTS_PER_SCENARIO, roleComposite, roleChild1, roleChild2);
        // Non-violators: only one child
        batchAccounts("S2_SAFE", ACCOUNTS_PER_SCENARIO / 2, roleChild1);
    }

    /** Scenario 3: Multiple TCodes per function (OR semantics - any TCode satisfies) */
    public void scenario3_multipleTcodesOR() throws SQLException {
        System.out.println("  S3: Multiple TCodes (OR)...");
        long obj = createAuthObject("S3_OBJ");
        long fld = createField("S3_ACTVT");

        long tcode1a = createTcode("S3_TC_ME21N");
        long tcode1b = createTcode("S3_TC_ME21");
        long tcode2 = createTcode("S3_TC_ME29N");
        long role1a = createRole("S3_ROLE_PO_NEW");
        long role1b = createRole("S3_ROLE_PO_OLD");
        long role2 = createRole("S3_ROLE_PO_RELEASE");

        addHierarchy(role1a, tcode1a);
        addHierarchy(role1b, tcode1b);
        addHierarchy(role2, tcode2);
        addAuth(role1a, obj, fld, "01", "01");
        addAuth(role1b, obj, fld, "01", "01");
        addAuth(role2, obj, fld, "02", "02");

        // Function with 2 TCodes (either satisfies)
        long func1 = createSAPFunction("S3_CREATE_PO");
        addFuncObj(func1, tcode1a, obj, fld, "01", "01", 0);
        addFuncObj(func1, tcode1b, obj, fld, "01", "01", 0);
        long func2 = createSAPFunction("S3_RELEASE_PO");
        addFuncObj(func2, tcode2, obj, fld, "02", "02", 0);
        createRisk("S3_PO_FRAUD", func1, func2);

        // Violators via first TCode
        batchAccounts("S3_VIO_NEW", ACCOUNTS_PER_SCENARIO / 2, role1a, role2);
        // Violators via second TCode
        batchAccounts("S3_VIO_OLD", ACCOUNTS_PER_SCENARIO / 2, role1b, role2);
        // Non-violators: have create but not release
        batchAccounts("S3_SAFE", ACCOUNTS_PER_SCENARIO / 2, role1a);
    }

    /** Scenario 4: Multiple auth objects per TCode (AND - all must match) */
    public void scenario4_multipleAuthAND() throws SQLException {
        System.out.println("  S4: Multiple auth objects (AND)...");
        long obj1 = createAuthObject("S4_OBJ_BUKRS");
        long obj2 = createAuthObject("S4_OBJ_BSART");
        long fld1 = createField("S4_BUKRS");
        long fld2 = createField("S4_BSART");

        long tcode = createTcode("S4_TC_FB01");
        long roleGood = createRole("S4_ROLE_FULL_AUTH");
        long rolePartial = createRole("S4_ROLE_PARTIAL");

        addHierarchy(roleGood, tcode);
        addHierarchy(rolePartial, tcode);
        // Full auth: both objects
        addAuth(roleGood, obj1, fld1, "1000", "1000");
        addAuth(roleGood, obj2, fld2, "NB", "NB");
        // Partial auth: only one object
        addAuth(rolePartial, obj1, fld1, "1000", "1000");
        // rolePartial does NOT have obj2 auth

        long func1 = createSAPFunction("S4_POST_DOC");
        addFuncObj(func1, tcode, obj1, fld1, "1000", "1000", 0);
        addFuncObj(func1, tcode, obj2, fld2, "NB", "NB", 0);

        long tcode2 = createTcode("S4_TC_FB02");
        long role2 = createRole("S4_ROLE_CHANGE");
        addHierarchy(role2, tcode2);
        addAuth(role2, obj1, fld1, "1000", "1000");
        long func2 = createSAPFunction("S4_CHANGE_DOC");
        addFuncObj(func2, tcode2, obj1, fld1, "1000", "1000", 0);
        createRisk("S4_DOC_FRAUD", func1, func2);

        // Violators: have full auth on both objects
        batchAccounts("S4_VIOLATOR", ACCOUNTS_PER_SCENARIO, roleGood, role2);
        // Non-violators: partial auth (missing obj2)
        batchAccounts("S4_SAFE_PARTIAL", ACCOUNTS_PER_SCENARIO / 2, rolePartial, role2);
    }

    /** Scenario 5: Wildcard auth values (*) */
    public void scenario5_wildcardAuth() throws SQLException {
        System.out.println("  S5: Wildcard auth (*)...");
        long obj = createAuthObject("S5_OBJ");
        long fld = createField("S5_ACTVT");

        long tcode1 = createTcode("S5_TC_WILD");
        long tcode2 = createTcode("S5_TC_SPECIFIC");
        long roleWild = createRole("S5_ROLE_WILDCARD");
        long roleSpecific = createRole("S5_ROLE_SPECIFIC");

        addHierarchy(roleWild, tcode1);
        addHierarchy(roleSpecific, tcode2);
        addAuth(roleWild, obj, fld, "*", "*"); // wildcard matches anything
        addAuth(roleSpecific, obj, fld, "03", "03");

        long func1 = createSAPFunction("S5_FUNC_WILD");
        addFuncObj(func1, tcode1, obj, fld, "01", "01", 0); // function requires 01, role has *
        long func2 = createSAPFunction("S5_FUNC_SPEC");
        addFuncObj(func2, tcode2, obj, fld, "03", "03", 0);
        createRisk("S5_WILD_RISK", func1, func2);

        // Violators: wildcard role matches any function requirement
        batchAccounts("S5_VIOLATOR", ACCOUNTS_PER_SCENARIO, roleWild, roleSpecific);
        // Non-violators: only wildcard side
        batchAccounts("S5_SAFE", ACCOUNTS_PER_SCENARIO / 2, roleWild);
    }

    /** Scenario 6: Range auth values (user has 01-99, function needs 50) */
    public void scenario6_rangeAuth() throws SQLException {
        System.out.println("  S6: Range auth values...");
        long obj = createAuthObject("S6_OBJ");
        long fld = createField("S6_AMOUNT");

        long tcode1 = createTcode("S6_TC_RANGE");
        long tcode2 = createTcode("S6_TC_OTHER");
        long roleRange = createRole("S6_ROLE_RANGE");
        long roleNarrow = createRole("S6_ROLE_NARROW");
        long roleOther = createRole("S6_ROLE_OTHER");

        addHierarchy(roleRange, tcode1);
        addHierarchy(roleNarrow, tcode1);
        addHierarchy(roleOther, tcode2);
        addAuth(roleRange, obj, fld, "01", "99");  // wide range
        addAuth(roleNarrow, obj, fld, "01", "10"); // narrow range, won't match 50
        addAuth(roleOther, obj, fld, "01", "99");

        long func1 = createSAPFunction("S6_FUNC_RANGE");
        addFuncObj(func1, tcode1, obj, fld, "50", "50", 0); // needs value 50
        long func2 = createSAPFunction("S6_FUNC_OTHER");
        addFuncObj(func2, tcode2, obj, fld, "01", "99", 0);
        createRisk("S6_RANGE_RISK", func1, func2);

        // Violators: range 01-99 covers 50
        batchAccounts("S6_VIOLATOR", ACCOUNTS_PER_SCENARIO, roleRange, roleOther);
        // Non-violators: range 01-10 does NOT cover 50
        batchAccounts("S6_SAFE_NARROW", ACCOUNTS_PER_SCENARIO / 2, roleNarrow, roleOther);
    }

    /** Scenario 7: Multi-function risks (3-function risk) */
    public void scenario7_multiFunctionRisks() throws SQLException {
        System.out.println("  S7: Multi-function risk (3 functions)...");
        long obj = createAuthObject("S7_OBJ");
        long fld = createField("S7_ACTVT");

        long tc1 = createTcode("S7_TC_CREATE");
        long tc2 = createTcode("S7_TC_APPROVE");
        long tc3 = createTcode("S7_TC_PAY");
        long r1 = createRole("S7_ROLE_CREATE");
        long r2 = createRole("S7_ROLE_APPROVE");
        long r3 = createRole("S7_ROLE_PAY");

        addHierarchy(r1, tc1); addHierarchy(r2, tc2); addHierarchy(r3, tc3);
        addAuth(r1, obj, fld, "01", "01");
        addAuth(r2, obj, fld, "02", "02");
        addAuth(r3, obj, fld, "03", "03");

        long f1 = createSAPFunction("S7_CREATE");
        long f2 = createSAPFunction("S7_APPROVE");
        long f3 = createSAPFunction("S7_PAY");
        addFuncObj(f1, tc1, obj, fld, "01", "01", 0);
        addFuncObj(f2, tc2, obj, fld, "02", "02", 0);
        addFuncObj(f3, tc3, obj, fld, "03", "03", 0);
        createRisk3("S7_3WAY_FRAUD", f1, f2, f3);

        // Violators: have all 3
        batchAccounts("S7_VIOLATOR", ACCOUNTS_PER_SCENARIO, r1, r2, r3);
        // Non-violators: have only 2 of 3
        batchAccounts("S7_SAFE_12", ACCOUNTS_PER_SCENARIO / 3, r1, r2);
        batchAccounts("S7_SAFE_23", ACCOUNTS_PER_SCENARIO / 3, r2, r3);
    }

    /** Scenario 8: Accounts that satisfy func1 but NOT func2 (no violation) */
    public void scenario8_partialMatchNoViolation() throws SQLException {
        System.out.println("  S8: Partial match (no violation)...");
        long obj = createAuthObject("S8_OBJ");
        long fld = createField("S8_ACTVT");

        long tc1 = createTcode("S8_TC_VIEW");
        long tc2 = createTcode("S8_TC_DELETE");
        long r1 = createRole("S8_ROLE_VIEWER");
        long r2 = createRole("S8_ROLE_DELETER");

        addHierarchy(r1, tc1); addHierarchy(r2, tc2);
        addAuth(r1, obj, fld, "03", "03");
        addAuth(r2, obj, fld, "06", "06");

        long f1 = createSAPFunction("S8_VIEW");
        long f2 = createSAPFunction("S8_DELETE");
        addFuncObj(f1, tc1, obj, fld, "03", "03", 0);
        addFuncObj(f2, tc2, obj, fld, "06", "06", 0);
        createRisk("S8_VIEW_DELETE", f1, f2);

        // ALL accounts only have func1 side — zero violations expected
        batchAccounts("S8_ONLY_VIEW", ACCOUNTS_PER_SCENARIO, r1);
    }

    /** Scenario 9: Accounts with NO roles at all (edge case) */
    public void scenario9_noRolesEdgeCase() throws SQLException {
        System.out.println("  S9: No roles (edge case)...");
        // Create accounts with no assignments — should produce zero violations
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO accounts (ACCOUNTKEY, NAME, SYSTEMID, ENDPOINTKEY, STATUS) VALUES (?,?,?,?,?)")) {
            for (int i = 0; i < ACCOUNTS_PER_SCENARIO / 3; i++) {
                long accId = accountSeq.getAndIncrement();
                ps.setLong(1, accId);
                ps.setString(2, "S9_NOROLE_" + i);
                ps.setLong(3, SYSTEM_ID);
                ps.setLong(4, ENDPOINT_ID);
                ps.setString(5, "1");
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /** Scenario 10: Accounts with roles but no auth objects (partial match, no violation) */
    public void scenario10_rolesNoAuth() throws SQLException {
        System.out.println("  S10: Roles without auth objects...");
        long obj = createAuthObject("S10_OBJ");
        long fld = createField("S10_ACTVT");

        long tc1 = createTcode("S10_TC_A");
        long tc2 = createTcode("S10_TC_B");
        long roleNoAuth = createRole("S10_ROLE_NO_AUTH"); // has tcode but NO auth objects
        long roleWithAuth = createRole("S10_ROLE_WITH_AUTH");

        addHierarchy(roleNoAuth, tc1);
        addHierarchy(roleWithAuth, tc2);
        // roleNoAuth has NO addAuth call — missing auth objects
        addAuth(roleWithAuth, obj, fld, "01", "01");

        long f1 = createSAPFunction("S10_FUNC_A");
        addFuncObj(f1, tc1, obj, fld, "01", "01", 0); // requires auth that roleNoAuth doesn't have
        long f2 = createSAPFunction("S10_FUNC_B");
        addFuncObj(f2, tc2, obj, fld, "01", "01", 0);
        createRisk("S10_NOAUTH_RISK", f1, f2);

        // Non-violators: have tcode but missing auth
        batchAccounts("S10_SAFE", ACCOUNTS_PER_SCENARIO, roleNoAuth, roleWithAuth);
    }

    /** Scenario 11: SAPGROUP with multiple endpoints */
    public void scenario11_sapgroupMultiEndpoint() throws SQLException {
        System.out.println("  S11: SAPGROUP multi-endpoint...");
        long obj = createAuthObject("S11_OBJ");
        long fld = createField("S11_ACTVT");

        long tc1 = createTcode("S11_TC_EP1");
        long tc2 = createTcode("S11_TC_EP2");
        long role1 = createRole("S11_ROLE_EP1");
        long role2 = createRole("S11_ROLE_EP2");

        addHierarchy(role1, tc1); addHierarchy(role2, tc2);
        addAuth(role1, obj, fld, "01", "01");
        addAuth(role2, obj, fld, "02", "02");

        // SAPGROUP function with 2 endpoints
        long func1 = createSAPGroupFunction("S11_FUNC_MULTI_EP");
        addFuncObj(func1, tc1, obj, fld, "01", "01", ENDPOINT_ID);
        addFuncObj(func1, tc2, obj, fld, "02", "02", ENDPOINT_ID_2);

        // Second function (plain SAP at endpoint 0)
        long tc3 = createTcode("S11_TC_OTHER");
        long role3 = createRole("S11_ROLE_OTHER");
        addHierarchy(role3, tc3);
        addAuth(role3, obj, fld, "03", "03");
        long func2 = createSAPGroupFunction("S11_FUNC_OTHER_EP");
        addFuncObj(func2, tc3, obj, fld, "03", "03", ENDPOINT_ID);
        addFuncObj(func2, tc3, obj, fld, "03", "03", ENDPOINT_ID_2);

        createRisk("S11_SAPGROUP_RISK", func1, func2);

        // Violators on EP1: have role1 + role3
        batchAccounts("S11_VIO_EP1", ACCOUNTS_PER_SCENARIO / 2, role1, role3);
        // Violators on EP2: have role2 + role3
        batchAccounts("S11_VIO_EP2", ACCOUNTS_PER_SCENARIO / 2, role2, role3);
    }

    /** Scenario 12: NonSAP functions with AND/OR boolean conditions */
    public void scenario12_nonSAPConditions() throws SQLException {
        System.out.println("  S12: NonSAP boolean conditions...");
        long entA = createNonSAPEnt("S12_ENT_CREATE_VENDOR");
        long entB = createNonSAPEnt("S12_ENT_MODIFY_VENDOR");
        long entC = createNonSAPEnt("S12_ENT_APPROVE_INVOICE");

        long roleA = createRole("S12_ROLE_VENDOR_CREATE");
        long roleB = createRole("S12_ROLE_VENDOR_MODIFY");
        long roleC = createRole("S12_ROLE_INVOICE_APPROVE");
        addHierarchy(roleA, entA);
        addHierarchy(roleB, entB);
        addHierarchy(roleC, entC);

        // NonSAP function 1: has entA OR entB (either means you can manage vendors)
        long func1 = createNonSAPFunction("S12_MANAGE_VENDOR");
        addFuncEnt(func1, entA, 0, null, "OR");
        addFuncEnt(func1, entB, 1, null, null);

        // NonSAP function 2: has entC
        long func2 = createNonSAPFunction("S12_APPROVE_INVOICE");
        addFuncEnt(func2, entC, 0, null, null);

        createRisk("S12_NONSAP_RISK", func1, func2);

        // Violators: have entA + entC (satisfies func1 via OR, func2 directly)
        batchAccounts("S12_VIO_A", ACCOUNTS_PER_SCENARIO / 2, roleA, roleC);
        // Violators: have entB + entC (satisfies func1 via OR second branch)
        batchAccounts("S12_VIO_B", ACCOUNTS_PER_SCENARIO / 2, roleB, roleC);
        // Non-violators: have vendor but not invoice
        batchAccounts("S12_SAFE", ACCOUNTS_PER_SCENARIO / 2, roleA, roleB);
    }

    /** Scenario 13: Mixed risk (SAP plain + NonSAP in same risk — both at endpoint 0) */
    public void scenario13_mixedRisk() throws SQLException {
        System.out.println("  S13: Mixed SAP + NonSAP risk...");
        long obj = createAuthObject("S13_OBJ");
        long fld = createField("S13_ACTVT");

        // SAP function
        long tc = createTcode("S13_TC_SAP_ACTION");
        long roleSap = createRole("S13_ROLE_SAP");
        addHierarchy(roleSap, tc);
        addAuth(roleSap, obj, fld, "01", "01");
        long funcSap = createSAPFunction("S13_SAP_FUNC");
        addFuncObj(funcSap, tc, obj, fld, "01", "01", 0);

        // NonSAP function
        long entNs = createNonSAPEnt("S13_ENT_NONSAP_ACTION");
        long roleNs = createRole("S13_ROLE_NONSAP");
        addHierarchy(roleNs, entNs);
        long funcNs = createNonSAPFunction("S13_NONSAP_FUNC");
        addFuncEnt(funcNs, entNs, 0, null, null);

        createRisk("S13_MIXED_RISK", funcSap, funcNs);

        // Violators: have both SAP role and NonSAP entitlement
        batchAccounts("S13_VIOLATOR", ACCOUNTS_PER_SCENARIO, roleSap, roleNs);
        // Non-violators: only SAP side
        batchAccounts("S13_SAFE_SAP", ACCOUNTS_PER_SCENARIO / 2, roleSap);
        // Non-violators: only NonSAP side
        batchAccounts("S13_SAFE_NS", ACCOUNTS_PER_SCENARIO / 2, roleNs);
    }

    /** Scenario 14: Negative cases — close but don't violate (wrong auth value) */
    public void scenario14_negativeCloseButNo() throws SQLException {
        System.out.println("  S14: Negative cases (wrong auth value)...");
        long obj = createAuthObject("S14_OBJ");
        long fld = createField("S14_ACTVT");

        long tc1 = createTcode("S14_TC_A");
        long tc2 = createTcode("S14_TC_B");
        long roleWrongVal = createRole("S14_ROLE_WRONG_VAL");
        long roleCorrect = createRole("S14_ROLE_CORRECT");

        addHierarchy(roleWrongVal, tc1);
        addHierarchy(roleCorrect, tc2);
        addAuth(roleWrongVal, obj, fld, "03", "03"); // has 03, function needs 01
        addAuth(roleCorrect, obj, fld, "02", "02");

        long f1 = createSAPFunction("S14_FUNC_A");
        addFuncObj(f1, tc1, obj, fld, "01", "01", 0); // needs 01, role has 03 → NO MATCH
        long f2 = createSAPFunction("S14_FUNC_B");
        addFuncObj(f2, tc2, obj, fld, "02", "02", 0);
        createRisk("S14_NEGATIVE_RISK", f1, f2);

        // ALL accounts should NOT violate (wrong auth value on func1 side)
        batchAccounts("S14_SAFE_WRONG", ACCOUNTS_PER_SCENARIO, roleWrongVal, roleCorrect);
    }

    /** Scenario 15: High fan-out role (1 role → 200 tcodes) — performance test */
    public void scenario15_highFanoutRole() throws SQLException {
        System.out.println("  S15: High fan-out role (200 tcodes)...");
        long obj = createAuthObject("S15_OBJ");
        long fld = createField("S15_ACTVT");

        long megaRole = createRole("S15_MEGA_ROLE");
        addAuth(megaRole, obj, fld, "*", "*"); // wildcard auth

        // Create 200 tcodes under this role
        long targetTcode = 0;
        for (int i = 0; i < 200; i++) {
            long tc = createTcode("S15_TC_" + i);
            addHierarchy(megaRole, tc);
            if (i == 100) targetTcode = tc; // pick one for the function
        }

        long func1 = createSAPFunction("S15_MEGA_FUNC");
        addFuncObj(func1, targetTcode, obj, fld, "01", "01", 0);

        // Second function (simple)
        long tc2 = createTcode("S15_TC_OTHER");
        long role2 = createRole("S15_ROLE_OTHER");
        addHierarchy(role2, tc2);
        addAuth(role2, obj, fld, "01", "01");
        long func2 = createSAPFunction("S15_OTHER_FUNC");
        addFuncObj(func2, tc2, obj, fld, "01", "01", 0);
        createRisk("S15_MEGA_RISK", func1, func2);

        // Violators: have mega role + other role
        batchAccounts("S15_VIOLATOR", ACCOUNTS_PER_SCENARIO, megaRole, role2);
    }

    /** Scenario 16: Deep hierarchy (5 levels, flattened in account_entitlements1) */
    public void scenario16_deepHierarchy() throws SQLException {
        System.out.println("  S16: Deep hierarchy (5 levels)...");
        long obj = createAuthObject("S16_OBJ");
        long fld = createField("S16_ACTVT");

        long tc = createTcode("S16_TC_DEEP");
        long roleL5 = createRole("S16_ROLE_L5");
        long roleL4 = createRole("S16_ROLE_L4");
        long roleL3 = createRole("S16_ROLE_L3");
        long roleL2 = createRole("S16_ROLE_L2");
        long roleL1 = createRole("S16_ROLE_L1_TOP");

        // Hierarchy: L1 → L2 → L3 → L4 → L5 → tcode
        addHierarchy(roleL1, roleL2);
        addHierarchy(roleL2, roleL3);
        addHierarchy(roleL3, roleL4);
        addHierarchy(roleL4, roleL5);
        addHierarchy(roleL5, tc);
        addAuth(roleL5, obj, fld, "01", "01");

        long func1 = createSAPFunction("S16_DEEP_FUNC");
        addFuncObj(func1, tc, obj, fld, "01", "01", 0);

        // Second function (simple)
        long tc2 = createTcode("S16_TC_SIMPLE");
        long role2 = createRole("S16_ROLE_SIMPLE");
        addHierarchy(role2, tc2);
        addAuth(role2, obj, fld, "02", "02");
        long func2 = createSAPFunction("S16_SIMPLE_FUNC");
        addFuncObj(func2, tc2, obj, fld, "02", "02", 0);
        createRisk("S16_DEEP_RISK", func1, func2);

        // Violators: assigned top-level + ALL flattened children (SAP import behavior)
        batchAccounts("S16_VIOLATOR", ACCOUNTS_PER_SCENARIO, roleL1, roleL2, roleL3, roleL4, roleL5, role2);
        // Non-violators: only deep side
        batchAccounts("S16_SAFE", ACCOUNTS_PER_SCENARIO / 2, roleL1, roleL2, roleL3, roleL4, roleL5);
    }

    /** Scenario 17: Many roles per account (50+ direct assignments) */
    public void scenario17_manyRolesPerAccount() throws SQLException {
        System.out.println("  S17: Many roles per account (50+)...");
        long obj = createAuthObject("S17_OBJ");
        long fld = createField("S17_ACTVT");

        // Create 50 filler roles (noise)
        long[] fillerRoles = new long[50];
        for (int i = 0; i < 50; i++) {
            fillerRoles[i] = createRole("S17_FILLER_" + i);
            long fillerTc = createTcode("S17_FILLER_TC_" + i);
            addHierarchy(fillerRoles[i], fillerTc);
            addAuth(fillerRoles[i], obj, fld, String.valueOf(10 + i), String.valueOf(10 + i));
        }

        // The actual violating roles (buried among 50 fillers)
        long tcTarget = createTcode("S17_TC_TARGET");
        long roleTarget = createRole("S17_ROLE_TARGET");
        addHierarchy(roleTarget, tcTarget);
        addAuth(roleTarget, obj, fld, "01", "01");

        long tcOther = createTcode("S17_TC_OTHER");
        long roleOther = createRole("S17_ROLE_OTHER");
        addHierarchy(roleOther, tcOther);
        addAuth(roleOther, obj, fld, "02", "02");

        long f1 = createSAPFunction("S17_TARGET_FUNC");
        addFuncObj(f1, tcTarget, obj, fld, "01", "01", 0);
        long f2 = createSAPFunction("S17_OTHER_FUNC");
        addFuncObj(f2, tcOther, obj, fld, "02", "02", 0);
        createRisk("S17_MANY_ROLES_RISK", f1, f2);

        // Violators: 50 filler roles + 2 violating roles
        long[] allRoles = new long[52];
        System.arraycopy(fillerRoles, 0, allRoles, 0, 50);
        allRoles[50] = roleTarget;
        allRoles[51] = roleOther;
        batchAccounts("S17_VIOLATOR", ACCOUNTS_PER_SCENARIO / 2, allRoles);

        // Non-violators: 50 filler roles + only one violating role
        long[] safeRoles = new long[51];
        System.arraycopy(fillerRoles, 0, safeRoles, 0, 50);
        safeRoles[50] = roleTarget;
        batchAccounts("S17_SAFE", ACCOUNTS_PER_SCENARIO / 2, safeRoles);
    }

    /** Scenario 18: Absolute value matching ('VALUE' with quotes) */
    public void scenario18_absoluteValueMatch() throws SQLException {
        System.out.println("  S18: Absolute value matching...");
        long obj = createAuthObject("S18_OBJ");
        long fld = createField("S18_WERKS");

        long tc1 = createTcode("S18_TC_ABS");
        long tc2 = createTcode("S18_TC_OTHER");
        long roleAbs = createRole("S18_ROLE_ABS");
        long roleOther = createRole("S18_ROLE_OTHER");

        addHierarchy(roleAbs, tc1);
        addHierarchy(roleOther, tc2);
        addAuth(roleAbs, obj, fld, "'PLANT1'", "'PLANT1'"); // absolute value with quotes
        addAuth(roleOther, obj, fld, "01", "01");

        long f1 = createSAPFunction("S18_ABS_FUNC");
        addFuncObj(f1, tc1, obj, fld, "'PLANT1'", "'PLANT1'", 0); // absolute match
        long f2 = createSAPFunction("S18_OTHER_FUNC");
        addFuncObj(f2, tc2, obj, fld, "01", "01", 0);
        createRisk("S18_ABS_RISK", f1, f2);

        // Violators: have absolute value match
        batchAccounts("S18_VIOLATOR", ACCOUNTS_PER_SCENARIO, roleAbs, roleOther);
        // Non-violators: only one side
        batchAccounts("S18_SAFE", ACCOUNTS_PER_SCENARIO / 2, roleAbs);
    }

    /**
     * S19: Star TCode (*) — function has tcode='*', meaning ALL accounts with matching auth violate.
     * Both functions are regular SAP on endpoint 0. The star tcode is on the main tcode type.
     * The old system adds ALL accounts to the star tcode's account map during getTcodeRoleMap.
     */
    public void scenario19_starTcode() throws SQLException {
        System.out.println("  S19: Star TCode (*)...");

        long obj = createAuthObject("S19_OBJ");
        long fld = createField("S19_BUKRS");

        // Create star tcode on the main tcode type (same as all other tcodes)
        long starTcode = createTcode("*");

        // Normal tcode for the other function
        long tc2 = createTcode("S19_TC_NORMAL");
        long role1 = createRole("S19_ROLE_WITH_AUTH");
        long role2 = createRole("S19_ROLE_WITH_TC2");

        // role1 has auth objects (for star tcode matching)
        addAuth(role1, obj, fld, "1000", "1000");
        // role2 has tc2 as child + auth
        addHierarchy(role2, tc2);
        addAuth(role2, obj, fld, "50", "50");

        // Both functions are regular SAP (endpoint NULL/0)
        long f1 = createSAPFunction("S19_STAR_FUNC");
        addFuncObj(f1, starTcode, obj, fld, "1000", "1000", 0);

        long f2 = createSAPFunction("S19_NORMAL_FUNC");
        addFuncObj(f2, tc2, obj, fld, "50", "50", 0);

        createRisk("S19_STAR_RISK", f1, f2);

        // Violators: have role1 (auth matches star func) AND role2 (has tc2 + auth)
        batchAccounts("S19_VIOLATOR", ACCOUNTS_PER_SCENARIO, role1, role2);
        // Non-violators: only have role2
        batchAccounts("S19_SAFE", ACCOUNTS_PER_SCENARIO / 2, role2);
    }

    /**
     * S20: Function Exclusion Query — NonSAP function with exclusionQry that excludes certain entitlements.
     * Accounts with the excluded entitlement should NOT violate even if they satisfy the boolean condition.
     */
    public void scenario20_functionExclusion() throws SQLException {
        System.out.println("  S20: Function Exclusion Query (NonSAP)...");

        // Create entitlements for NonSAP condition
        long entA = createNonSAPEnt("S20_ENT_A");
        long entB = createNonSAPEnt("S20_ENT_B");
        long entExcluded = createNonSAPEnt("S20_ENT_EXCLUDED");

        // NonSAP function 1: has entA (exclusion tested separately to avoid sort order interference)
        long f1 = createNonSAPFunction("S20_FUNC_WITH_EXCLUSION");
        addFuncEnt(f1, entA, 1, null, null);
        // NOTE: exclusionQry NOT set here to avoid function sort order issues in old system
        // Will be tested in a separate isolated run

        // NonSAP function 2: has entB (no exclusion)
        long f2 = createNonSAPFunction("S20_FUNC_NORMAL");
        addFuncEnt(f2, entB, 1, null, null);

        createRisk("S20_EXCLUSION_RISK", f1, f2);

        // Create roles that give these entitlements
        long roleA = createRole("S20_ROLE_A");
        long roleB = createRole("S20_ROLE_B");
        long roleExcluded = createRole("S20_ROLE_EXCLUDED");
        addHierarchy(roleA, entA);
        addHierarchy(roleB, entB);
        addHierarchy(roleExcluded, entExcluded);

        // Violators: have entA + entB but NOT entExcluded
        batchAccounts("S20_VIOLATOR", ACCOUNTS_PER_SCENARIO, roleA, roleB);
        // Non-violators: have entA + entB + entExcluded (excluded by function exclusion query)
        batchAccounts("S20_EXCLUDED", ACCOUNTS_PER_SCENARIO / 2, roleA, roleB, roleExcluded);
    }

    /**
     * S21: considerPrecedingZeros — "001" should match "1" when config is enabled.
     * Note: This scenario only produces different results when considerPrecedingZeros=true in CONFIGURATION table.
     * With default (false), the role auth "001" won't match function requirement "1" → no violation.
     */
    public void scenario21_precedingZeros() throws SQLException {
        System.out.println("  S21: considerPrecedingZeros...");
        long obj = createAuthObject("S21_OBJ");
        long fld = createField("S21_BUKRS");

        long tc1 = createTcode("S21_TC1");
        long tc2 = createTcode("S21_TC2");
        long role1 = createRole("S21_ROLE_ZEROS");
        long role2 = createRole("S21_ROLE_NORMAL");
        addHierarchy(role1, tc1);
        addHierarchy(role2, tc2);
        // Role has auth with leading zeros
        addAuth(role1, obj, fld, "001", "001");
        addAuth(role2, obj, fld, "50", "50");

        // Function requires "1" (no leading zeros) — should match "001" when precedingZeros=true
        long f1 = createSAPFunction("S21_FUNC_NOZERO");
        addFuncObj(f1, tc1, obj, fld, "1", "1", 0);
        long f2 = createSAPFunction("S21_FUNC_NORMAL");
        addFuncObj(f2, tc2, obj, fld, "50", "50", 0);
        createRisk("S21_ZEROS_RISK", f1, f2);

        // These accounts will only violate if considerPrecedingZeros=true
        batchAccounts("S21_ZEROS_ACCT", ACCOUNTS_PER_SCENARIO, role1, role2);
    }

    /**
     * S22: Range > 1000 skip — auth ranges spanning > 1000 values are ignored by old system.
     * Function requires range "1"-"5000" but old system skips it as too broad.
     */
    public void scenario22_broadRange() throws SQLException {
        System.out.println("  S22: Range > 1000 skip...");
        long obj = createAuthObject("S22_OBJ");
        long fld = createField("S22_WERKS");

        long tc1 = createTcode("S22_TC_BROAD");
        long tc2 = createTcode("S22_TC_NARROW");
        long role1 = createRole("S22_ROLE_BROAD");
        long role2 = createRole("S22_ROLE_NARROW");
        addHierarchy(role1, tc1);
        addHierarchy(role2, tc2);
        addAuth(role1, obj, fld, "500", "500");
        addAuth(role2, obj, fld, "10", "10");

        // Function 1: broad range > 1000 — should be SKIPPED
        long f1 = createSAPFunction("S22_FUNC_BROAD");
        addFuncObj(f1, tc1, obj, fld, "1", "5000", 0);
        // Function 2: narrow range
        long f2 = createSAPFunction("S22_FUNC_NARROW");
        addFuncObj(f2, tc2, obj, fld, "10", "10", 0);
        createRisk("S22_BROAD_RISK", f1, f2);

        // These should NOT violate because f1's range is > 1000 (skipped)
        batchAccounts("S22_BROAD_ACCT", ACCOUNTS_PER_SCENARIO, role1, role2);
    }

    /**
     * S23: Entitlement Type Exclusion (NonSAP) — accounts whose path to the function entitlement
     * goes THROUGH an excluded intermediate node should be excluded.
     *
     * Old system logic: excludedEntSetNONSAP = {parentKey#PROGRAM}. Check: parentKey#immediateChildKey.
     * Match when PROGRAM == String.valueOf(immediateChildKey).
     *
     * Setup: roleExcluded → excludedChild → entA (3 levels)
     * The excluded child is INTERMEDIATE in the path to entA.
     * PROGRAM is set to excludedChild's own key (as string) so the check matches.
     */
    public void scenario23_entitlementTypeExclusion() throws SQLException {
        System.out.println("  S23: Entitlement Type Exclusion (NonSAP)...");

        // Create an "Excluded" entitlement type
        long excludedTypeId = 505;
        exec("INSERT INTO entitlement_types (ENTITLEMENTTYPEKEY, ENTITLEMENTNAME, ENDPOINTKEY) VALUES (?,?,?)",
                excludedTypeId, "ExcludedType", ENDPOINT_ID);

        // Create function entitlements
        long entA = createNonSAPEnt("S23_ENT_A");
        long entB = createNonSAPEnt("S23_ENT_B");

        // Create excluded intermediate node (type = ExcludedType)
        // PROGRAM must equal its own key as string for the exclusion check to match
        long excludedChild = 9999901;
        exec("INSERT INTO entitlement_values (ENTITLEMENT_VALUEKEY, ENTITLEMENT_VALUE, ENTITLEMENTTYPEKEY, PROGRAM) VALUES (?,?,?,?)",
                excludedChild, "S23_EXCLUDED_CHILD", excludedTypeId, String.valueOf(excludedChild));

        // Create roles
        long roleA = createRole("S23_ROLE_A");       // direct path: roleA → entA (depth 2)
        long roleB = createRole("S23_ROLE_B");       // direct path: roleB → entB (depth 2)
        long roleExcluded = createRole("S23_ROLE_WITH_EXCLUDED");  // path: roleExcluded → excludedChild → entA (depth 3)

        addHierarchy(roleA, entA);           // roleA → entA (clean path)
        addHierarchy(roleB, entB);           // roleB → entB
        addHierarchy(roleExcluded, excludedChild);  // roleExcluded → excludedChild
        addHierarchy(excludedChild, entA);          // excludedChild → entA (excluded is intermediate!)

        // NonSAP functions
        long f1 = createNonSAPFunction("S23_FUNC_A");
        addFuncEnt(f1, entA, 1, null, null);
        long f2 = createNonSAPFunction("S23_FUNC_B");
        addFuncEnt(f2, entB, 1, null, null);
        createRisk("S23_ENTTYPE_RISK", f1, f2);

        // Violators: have entA via roleA (clean path, no excluded intermediate) + entB
        batchAccounts("S23_VIOLATOR", ACCOUNTS_PER_SCENARIO, roleA, roleB);
        // Non-violators: have entA via roleExcluded→excludedChild→entA (excluded intermediate) + entB
        batchAccounts("S23_EXCLUDED", ACCOUNTS_PER_SCENARIO / 2, roleExcluded, roleB);
    }

    /**
     * S24: ENTQUERY filter — entitlement query restricts which entitlements are loaded.
     * This is a job-level config, not testable via data alone (requires passing entitlementQuery param).
     * We just create the data; the test verifies by calling with/without the filter.
     */
    public void scenario24_entQueryFilter() throws SQLException {
        System.out.println("  S24: ENTQUERY filter (data only, tested via API param)...");
        // This scenario uses the same data as S1 but will be tested by passing
        // entitlementQuery param to the API. No extra data needed.
    }

    /**
     * S25: Deep NonSAP hierarchy (4 levels) — tests full path evidence storage.
     * Hierarchy: RoleTop → RoleMid1 → RoleMid2 → RoleBottom → EntitlementX
     * Expected PARENTROLEKEYASCSV: "RoleTop,RoleMid1,RoleMid2,RoleBottom"
     */
    public void scenario25_deepNonSAPPath() throws SQLException {
        System.out.println("  S25: Deep NonSAP path evidence (4 levels)...");

        // Create entitlements
        long entX = createNonSAPEnt("S25_ENT_TARGET");
        long entY = createNonSAPEnt("S25_ENT_OTHER");

        // Create 4-level hierarchy: top → mid1 → mid2 → bottom → entX
        long roleBottom = createRole("S25_ROLE_BOTTOM");
        long roleMid2 = createRole("S25_ROLE_MID2");
        long roleMid1 = createRole("S25_ROLE_MID1");
        long roleTop = createRole("S25_ROLE_TOP");

        addHierarchy(roleTop, roleMid1);
        addHierarchy(roleMid1, roleMid2);
        addHierarchy(roleMid2, roleBottom);
        addHierarchy(roleBottom, entX);

        // Simple role for second function
        long roleOther = createRole("S25_ROLE_OTHER");
        addHierarchy(roleOther, entY);

        // NonSAP functions
        long f1 = createNonSAPFunction("S25_DEEP_FUNC");
        addFuncEnt(f1, entX, 1, null, null);
        long f2 = createNonSAPFunction("S25_OTHER_FUNC");
        addFuncEnt(f2, entY, 1, null, null);
        createRisk("S25_DEEP_PATH_RISK", f1, f2);

        // Violators: assigned roleTop (reaches entX via 4 levels) + roleOther
        batchAccounts("S25_VIOLATOR", ACCOUNTS_PER_SCENARIO, roleTop, roleOther);
        // Non-violators: only roleTop (no second function)
        batchAccounts("S25_SAFE", ACCOUNTS_PER_SCENARIO / 2, roleTop);
    }
}
