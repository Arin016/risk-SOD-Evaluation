package com.saviynt.sod.testdata;

import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Generates large-scale SOD test data covering all evaluation scenarios.
 * Inserts into ecmg6new DB under system 200, endpoint 200, ruleset 200.
 *
 * Scenarios covered:
 * 1. Direct role → tcode (flat, 1 level)
 * 2. Composite role + flattened children
 * 3. Multiple TCodes per function (OR semantics)
 * 4. Multiple auth objects per TCode (AND semantics)
 * 5. Wildcard auth values (*)
 * 6. Range auth values (01-99)
 * 7. Multi-function risks (2, 3, 4, 5 functions)
 * 8. Accounts that satisfy func1 but NOT func2 (no violation)
 * 9. Accounts with NO roles (edge case)
 * 10. Accounts with roles but no auth objects (partial match)
 * 11. Multiple endpoints (SAPGROUP)
 * 12. NonSAP functions (AND/OR boolean conditions)
 * 13. Mixed risks (SAP + NonSAP in same risk)
 * 14. Negative cases (close but don't violate)
 * 15. High fan-out roles (1 role → 500 tcodes)
 * 16. Deep hierarchy (flattened, 5 levels in ENTITLEMENTS2)
 * 17. Many roles per account (50+ direct assignments)
 * 18. Absolute value matching ('VALUE' with quotes)
 */
public class TestDataGenerator {

    // === ID RANGES (well above existing max IDs) ===
    static final long SYSTEM_ID = 200;
    static final long ENDPOINT_ID = 200;
    static final long ENDPOINT_ID_2 = 201; // for SAPGROUP scenarios
    static final long RULESET_ID = 200;

    static final long ENT_TYPE_TCODE = 500;
    static final long ENT_TYPE_SAPROLE = 501;
    static final long ENT_TYPE_NONSAP = 502; // for NonSAP entitlements

    // ID counters
    static final AtomicLong entValueSeq = new AtomicLong(3_000_000);
    static final AtomicLong ent2Seq = new AtomicLong(30_000_000);
    static final AtomicLong accountSeq = new AtomicLong(600_000);
    static final AtomicLong accEntSeq = new AtomicLong(500_000);
    static final AtomicLong funcSeq = new AtomicLong(500);
    static final AtomicLong riskSeq = new AtomicLong(500);
    static final AtomicLong funcObjSeq = new AtomicLong(30_000);
    static final AtomicLong funcEntSeq = new AtomicLong(200);
    static final AtomicLong objSeq = new AtomicLong(60_000);
    static final AtomicLong fieldSeq = new AtomicLong(60_000);

    // === SCALE CONFIGURATION ===
    // Small: ACCOUNTS_PER_SCENARIO=600, BULK_ROLES=50, TCODES_PER_ROLE=5, BULK_FUNCS=10, BULK_RISKS=20, ROLES_PER_ACCOUNT=2
    // Hitachi: ACCOUNTS_PER_SCENARIO=3000, BULK_ROLES=5000, TCODES_PER_ROLE=100, BULK_FUNCS=100, BULK_RISKS=250, ROLES_PER_ACCOUNT=10
    static int ACCOUNTS_PER_SCENARIO;
    static int BULK_ROLES;
    static int TCODES_PER_ROLE;
    static int BULK_FUNCS;
    static int BULK_RISKS;
    static int ROLES_PER_ACCOUNT;

    static Connection conn;

    public static void main(String[] args) throws Exception {
        // Default: small (for correctness verification with old system)
        String profile = args.length > 0 ? args[0] : "small";

        if (profile.equals("hitachi")) {
            ACCOUNTS_PER_SCENARIO = 3000;   // ~72K total accounts
            BULK_ROLES = 5000;              // 5000 roles
            TCODES_PER_ROLE = 100;          // 500K graph edges
            BULK_FUNCS = 100;               // 135 total functions
            BULK_RISKS = 250;               // 267 total risks
            ROLES_PER_ACCOUNT = 10;         // ~720K role assignments
        } else { // "small" — for correctness verification
            ACCOUNTS_PER_SCENARIO = 600;    // ~14.4K total accounts
            BULK_ROLES = 50;                // 50 roles
            TCODES_PER_ROLE = 5;            // 250 graph edges
            BULK_FUNCS = 10;                // 45 total functions
            BULK_RISKS = 20;                // 37 total risks
            ROLES_PER_ACCOUNT = 2;          // ~28K role assignments
        }

        System.out.println("=== SOD Test Data Generator [" + profile + "] ===");
        System.out.println("Accounts/scenario=" + ACCOUNTS_PER_SCENARIO + ", BulkRoles=" + BULK_ROLES +
                ", TcodesPerRole=" + TCODES_PER_ROLE + ", BulkFuncs=" + BULK_FUNCS +
                ", BulkRisks=" + BULK_RISKS + ", RolesPerAccount=" + ROLES_PER_ACCOUNT);

        String url = "jdbc:mysql://127.0.0.1:3306/ecmg6new?rewriteBatchedStatements=true&allowPublicKeyRetrieval=true&useSSL=false";
        conn = DriverManager.getConnection(url, "root", "rootpass");
        conn.setAutoCommit(false);

        // Disable strict mode for this session
        try (Statement s = conn.createStatement()) {
            s.execute("SET SESSION sql_mode = ''");
            s.execute("SET SESSION FOREIGN_KEY_CHECKS = 0");
        }

        System.out.println("=== SOD Test Data Generator ===");
        System.out.println("Target: System " + SYSTEM_ID + ", Endpoint " + ENDPOINT_ID + ", Ruleset " + RULESET_ID);

        long start = System.currentTimeMillis();

        // Clean previous test data for this system
        cleanPreviousData();

        // Infrastructure
        createSecuritySystem();
        createEntitlementTypes();

        // Generate scenarios
        ScenarioBuilder sb = new ScenarioBuilder(conn);
        sb.scenario1_directRoleTcode();
        sb.scenario2_compositeRoleFlattened();
        sb.scenario3_multipleTcodesOR();
        sb.scenario4_multipleAuthAND();
        sb.scenario5_wildcardAuth();
        sb.scenario6_rangeAuth();
        sb.scenario7_multiFunctionRisks();
        sb.scenario8_partialMatchNoViolation();
        sb.scenario9_noRolesEdgeCase();
        sb.scenario10_rolesNoAuth();
        sb.scenario11_sapgroupMultiEndpoint();
        sb.scenario12_nonSAPConditions();
        sb.scenario13_mixedRisk();
        sb.scenario14_negativeCloseButNo();
        sb.scenario15_highFanoutRole();
        sb.scenario16_deepHierarchy();
        sb.scenario17_manyRolesPerAccount();
        sb.scenario18_absoluteValueMatch();
        sb.scenario19_starTcode();
        sb.scenario20_functionExclusion();
        sb.scenario21_precedingZeros();
        sb.scenario22_broadRange();
        sb.scenario23_entitlementTypeExclusion();
        sb.scenario24_entQueryFilter();
        sb.scenario25_deepNonSAPPath();

        // Scale up to production-level: add bulk functions, risks, auth, graph edges
        generateBulkFunctionsAndRisks(sb);

        conn.commit();

        long elapsed = System.currentTimeMillis() - start;
        System.out.println("\n=== DONE in " + elapsed + "ms ===");
        System.out.println("Total accounts: " + (accountSeq.get() - 600_000));
        System.out.println("Total entitlement_values: " + (entValueSeq.get() - 3_000_000));
        System.out.println("Total entitlements2 edges: " + (ent2Seq.get() - 30_000_000));
        System.out.println("Total functions: " + (funcSeq.get() - 500));
        System.out.println("Total risks: " + (riskSeq.get() - 500));

        conn.close();
    }

    /**
     * Generate production-scale bulk data to match Hitachi numbers.
     */
    static void generateBulkFunctionsAndRisks(ScenarioBuilder sb) throws Exception {
        System.out.println("  BULK: Generating " + BULK_FUNCS + " extra functions...");

        // Create shared auth objects and fields
        long[] bulkObjs = new long[20];
        long[] bulkFields = new long[20];
        for (int i = 0; i < 20; i++) {
            bulkObjs[i] = sb.createAuthObject("BULK_OBJ_" + i);
            bulkFields[i] = sb.createField("BULK_FLD_" + i);
        }

        // Create roles with tcodes and auth entries
        System.out.println("  BULK: Creating " + BULK_ROLES + " roles with " + TCODES_PER_ROLE + " tcodes each...");
        long[] bulkRoles = new long[BULK_ROLES];
        long[][] roleTcodes = new long[BULK_ROLES][TCODES_PER_ROLE];
        for (int r = 0; r < BULK_ROLES; r++) {
            bulkRoles[r] = sb.createRole("BULK_ROLE_" + r);
            for (int t = 0; t < TCODES_PER_ROLE; t++) {
                roleTcodes[r][t] = sb.createTcode("BULK_TC_" + r + "_" + t);
                sb.addHierarchy(bulkRoles[r], roleTcodes[r][t]);
            }
            for (int a = 0; a < 10; a++) {
                sb.addAuth(bulkRoles[r], bulkObjs[a % 20], bulkFields[a % 20],
                        String.valueOf(1 + (r + a) % 99), String.valueOf(1 + (r + a) % 99));
            }
            if (r % 500 == 0 && r > 0) { conn.commit(); System.out.println("    ... " + r + "/" + BULK_ROLES + " roles"); }
        }
        conn.commit();

        // Create extra SAP functions
        System.out.println("  BULK: Creating " + BULK_FUNCS + " extra functions...");
        long[] bulkFuncs = new long[BULK_FUNCS];
        for (int f = 0; f < BULK_FUNCS; f++) {
            bulkFuncs[f] = sb.createSAPFunction("BULK_FUNC_" + f);
            int numConditions = 10 + (f % 11);
            int roleIdx = f % BULK_ROLES;
            for (int c = 0; c < numConditions; c++) {
                sb.addFuncObj(bulkFuncs[f], roleTcodes[roleIdx][c % TCODES_PER_ROLE],
                        bulkObjs[c % 20], bulkFields[c % 20],
                        String.valueOf(1 + (roleIdx + c) % 99), String.valueOf(1 + (roleIdx + c) % 99), 0);
            }
        }
        conn.commit();

        // Create extra risks
        System.out.println("  BULK: Creating " + BULK_RISKS + " extra risks...");
        for (int r = 0; r < BULK_RISKS; r++) {
            sb.createRisk("BULK_RISK_" + r, bulkFuncs[r % BULK_FUNCS], bulkFuncs[(r + BULK_FUNCS/2) % BULK_FUNCS]);
        }
        conn.commit();

        // Assign bulk roles to all accounts
        System.out.println("  BULK: Assigning " + ROLES_PER_ACCOUNT + " bulk roles per account...");
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO account_entitlements1 (ACCENTKEY, ACCOUNTKEY, ENTITLEMENT_VALUEKEY) VALUES (?,?,?)")) {
            long endAcc = accountSeq.get();
            int count = 0;
            for (long acc = 600000; acc < endAcc; acc++) {
                for (int i = 0; i < ROLES_PER_ACCOUNT; i++) {
                    int roleIdx = (int) ((acc + i * 7) % BULK_ROLES);
                    ps.setLong(1, accEntSeq.getAndIncrement());
                    ps.setLong(2, acc);
                    ps.setLong(3, bulkRoles[roleIdx]);
                    ps.addBatch();
                    count++;
                    if (count % 10000 == 0) ps.executeBatch();
                }
            }
            ps.executeBatch();
            System.out.println("  BULK: " + count + " role assignments added.");
        }
        conn.commit();

        System.out.println("  BULK: Total functions=" + (funcSeq.get() - 500) +
                ", risks=" + (riskSeq.get() - 500) + ", graph edges=" + (ent2Seq.get() - 30_000_000));
    }

    static void cleanPreviousData() throws SQLException {
        System.out.println("Cleaning previous test data for system " + SYSTEM_ID + "...");
        Statement s = conn.createStatement();
        s.executeUpdate("DELETE FROM sodrisk_objects WHERE SODKEY IN (SELECT SODKEY FROM sodrisks WHERE RISKKEY IN (SELECT RISKID FROM risks WHERE RULESETKEY = " + RULESET_ID + "))");
        s.executeUpdate("DELETE FROM sodrisk_entitlement WHERE SODKEY IN (SELECT SODKEY FROM sodrisks WHERE RISKKEY IN (SELECT RISKID FROM risks WHERE RULESETKEY = " + RULESET_ID + "))");
        s.executeUpdate("DELETE FROM sodrisks WHERE RISKKEY IN (SELECT RISKID FROM risks WHERE RULESETKEY = " + RULESET_ID + ")");
        s.executeUpdate("DELETE FROM risks WHERE RULESETKEY = " + RULESET_ID);
        s.executeUpdate("DELETE FROM function_objects WHERE FUNCTIONKEY IN (SELECT FUNCTIONKEY FROM functions WHERE RULESETKEY = " + RULESET_ID + ")");
        s.executeUpdate("DELETE FROM function_entitlements WHERE FUNCTIONKEY IN (SELECT FUNCTIONKEY FROM functions WHERE RULESETKEY = " + RULESET_ID + ")");
        s.executeUpdate("DELETE FROM functions WHERE RULESETKEY = " + RULESET_ID);
        s.executeUpdate("DELETE FROM account_entitlements1 WHERE ACCOUNTKEY IN (SELECT ACCOUNTKEY FROM accounts WHERE SYSTEMID = " + SYSTEM_ID + ")");
        s.executeUpdate("DELETE FROM entitlement_objects WHERE ENTITLEMENT_VALUEKEY IN (SELECT ENTITLEMENT_VALUEKEY FROM entitlement_values WHERE ENTITLEMENTTYPEKEY IN (" + ENT_TYPE_TCODE + "," + ENT_TYPE_SAPROLE + "," + ENT_TYPE_NONSAP + ",505))");
        s.executeUpdate("DELETE FROM entitlements2 WHERE ENT2KEY >= 30000000");
        s.executeUpdate("DELETE FROM entitlement_values WHERE ENTITLEMENTTYPEKEY IN (" + ENT_TYPE_TCODE + "," + ENT_TYPE_SAPROLE + "," + ENT_TYPE_NONSAP + ",505)");
        s.executeUpdate("DELETE FROM accounts WHERE SYSTEMID = " + SYSTEM_ID);
        s.executeUpdate("DELETE FROM entitlement_types WHERE ENTITLEMENTTYPEKEY IN (" + ENT_TYPE_TCODE + "," + ENT_TYPE_SAPROLE + "," + ENT_TYPE_NONSAP + ",505)");
        s.executeUpdate("DELETE FROM access_objects WHERE SYSTEMID = " + SYSTEM_ID);
        s.executeUpdate("DELETE FROM fields WHERE FIELDKEY >= 60000");
        s.executeUpdate("DELETE FROM endpoints WHERE ENDPOINTKEY IN (" + ENDPOINT_ID + "," + ENDPOINT_ID_2 + ")");
        s.executeUpdate("DELETE FROM rulesets WHERE RULESETKEY = " + RULESET_ID);
        s.executeUpdate("DELETE FROM securitysystems WHERE SYSTEMKEY = " + SYSTEM_ID);
        conn.commit();
        System.out.println("  Cleaned.");
    }

    static void createSecuritySystem() throws SQLException {
        Statement s = conn.createStatement();
        s.executeUpdate("INSERT INTO securitysystems (SYSTEMKEY, SYSTEMNAME, STATUS, DEFAULTSYSTEM) VALUES (" + SYSTEM_ID + ", 'TEST_BENCH_SAP', 1, 0)");
        s.executeUpdate("INSERT INTO endpoints (ENDPOINTKEY, ENDPOINTNAME, SECURITYSYSTEMKEY, STATUS) VALUES (" + ENDPOINT_ID + ", 'TEST_BENCH_EP1', " + SYSTEM_ID + ", 1)");
        s.executeUpdate("INSERT INTO endpoints (ENDPOINTKEY, ENDPOINTNAME, SECURITYSYSTEMKEY, STATUS) VALUES (" + ENDPOINT_ID_2 + ", 'TEST_BENCH_EP2', " + SYSTEM_ID + ", 1)");
        s.executeUpdate("INSERT INTO rulesets (RULESETKEY, RULESET, DEFAULTRULESET) VALUES (" + RULESET_ID + ", 'TEST_BENCH_RULESET', 0)");
        System.out.println("  Created system/endpoints/ruleset.");
    }

    static void createEntitlementTypes() throws SQLException {
        Statement s = conn.createStatement();
        s.executeUpdate("INSERT INTO entitlement_types (ENTITLEMENTTYPEKEY, ENTITLEMENTNAME, ENDPOINTKEY, SYSTEMKEY) VALUES (" + ENT_TYPE_TCODE + ", 'tcode', " + ENDPOINT_ID + ", " + SYSTEM_ID + ")");
        s.executeUpdate("INSERT INTO entitlement_types (ENTITLEMENTTYPEKEY, ENTITLEMENTNAME, ENDPOINTKEY, SYSTEMKEY) VALUES (" + ENT_TYPE_SAPROLE + ", 'saprole', " + ENDPOINT_ID + ", " + SYSTEM_ID + ")");
        s.executeUpdate("INSERT INTO entitlement_types (ENTITLEMENTTYPEKEY, ENTITLEMENTNAME, ENDPOINTKEY, SYSTEMKEY) VALUES (" + ENT_TYPE_NONSAP + ", 'nonsap_ent', " + ENDPOINT_ID + ", " + SYSTEM_ID + ")");
        System.out.println("  Created entitlement types.");
    }
}
