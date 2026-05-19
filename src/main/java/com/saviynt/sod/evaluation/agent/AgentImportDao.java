package com.saviynt.sod.evaluation.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * DAO for inserting agent data into existing ECM SOD tables.
 * All agent data lives under securitySystemId=300, endpointKey=300.
 *
 * Schema notes (Saviynt ECM):
 * - users: USERKEY, USERNAME, ENABLED (bit), PASSWORD (varchar, required)
 * - accounts: ACCOUNTKEY, NAME, ENDPOINTKEY, SYSTEMID, STATUS (varchar), ORPHAN (bit, required)
 * - user_accounts: (USERKEY, ACCOUNTKEY) — PK is composite. FK: USERKEY→users.USERKEY AND USERKEY→accounts.ACCOUNTKEY
 *   This means every USERKEY must ALSO exist as an ACCOUNTKEY (self-account pattern).
 * - entitlement_values: ENTITLEMENT_VALUEKEY, ENTITLEMENTTYPEKEY, ENTITLEMENT_VALUE, ORPHAN (bit, required)
 * - entitlements2: ENT2KEY (auto), ENTITLEMENT_VALUE1KEY, ENTITLEMENT_VALUE2KEY
 * - entitlement_objects: id (auto), ENTITLEMENT_VALUEKEY, OBJECTKEY, FIELD_KEY, MINVALUE, MXVALUE, OBJECTDELETED
 * - account_entitlements1: ACCOUNTKEY→accounts, ENTITLEMENT_VALUEKEY→entitlement_values
 */
@Repository
public class AgentImportDao {

    private static final Logger log = LoggerFactory.getLogger(AgentImportDao.class);
    private final JdbcTemplate jdbc;

    static final long SECURITY_SYSTEM_KEY = 300;
    static final long ENDPOINT_KEY = 300;
    static final long ENT_TYPE_AGENT = 600;
    static final long ENT_TYPE_CAPABILITY = 601;
    static final long STAR_TCODE_KEY = 300_000_000L;

    public AgentImportDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Wipe ONLY agent SOD data (system 300). Does NOT touch any other system's data.
     * Deletes in correct FK dependency order.
     */
    public void clearAgentData() {
        log.info("Clearing existing agent SOD data (system 300)...");

        // 1. Delete violation detail rows referencing our entitlements (via TCODEKEY FK)
        jdbc.update("""
            DELETE se FROM sodrisk_entitlement se
            INNER JOIN entitlement_values ev ON se.TCODEKEY = ev.ENTITLEMENT_VALUEKEY
            WHERE ev.ENTITLEMENTTYPEKEY IN (?, ?)""", ENT_TYPE_AGENT, ENT_TYPE_CAPABILITY);

        // 2. Delete violation headers for our endpoint
        jdbc.update("DELETE FROM sodrisks WHERE ENDPOINTKEY = ?", ENDPOINT_KEY);

        // 3. Delete account→entitlement assignments (only our accounts)
        jdbc.update("""
            DELETE ae FROM account_entitlements1 ae
            INNER JOIN accounts a ON ae.ACCOUNTKEY = a.ACCOUNTKEY
            WHERE a.SYSTEMID = ?""", SECURITY_SYSTEM_KEY);

        // 4. Delete user_accounts links (only our accounts)
        jdbc.update("""
            DELETE ua FROM user_accounts ua
            INNER JOIN accounts a ON ua.ACCOUNTKEY = a.ACCOUNTKEY
            WHERE a.SYSTEMID = ?""", SECURITY_SYSTEM_KEY);

        // 5. Delete entitlement_objects on our entitlements
        jdbc.update("""
            DELETE eo FROM entitlement_objects eo
            INNER JOIN entitlement_values ev ON eo.ENTITLEMENT_VALUEKEY = ev.ENTITLEMENT_VALUEKEY
            WHERE ev.ENTITLEMENTTYPEKEY IN (?, ?)""", ENT_TYPE_AGENT, ENT_TYPE_CAPABILITY);

        // 6. Delete entitlements2 edges involving our entitlements
        jdbc.update("""
            DELETE e2 FROM entitlements2 e2
            INNER JOIN entitlement_values ev ON e2.ENTITLEMENT_VALUE1KEY = ev.ENTITLEMENT_VALUEKEY
            WHERE ev.ENTITLEMENTTYPEKEY IN (?, ?)""", ENT_TYPE_AGENT, ENT_TYPE_CAPABILITY);
        jdbc.update("""
            DELETE e2 FROM entitlements2 e2
            INNER JOIN entitlement_values ev ON e2.ENTITLEMENT_VALUE2KEY = ev.ENTITLEMENT_VALUEKEY
            WHERE ev.ENTITLEMENTTYPEKEY IN (?, ?)""", ENT_TYPE_AGENT, ENT_TYPE_CAPABILITY);

        // 7. Delete our accounts (the agent accounts in system 300)
        jdbc.update("DELETE FROM accounts WHERE SYSTEMID = ?", SECURITY_SYSTEM_KEY);

        // 8. Delete self-accounts for our fake users (ACCOUNTKEY = USERKEY pattern)
        //    These are accounts where NAME starts with 'AGENT::' or 'CROSS_AGENT::'
        jdbc.update("DELETE FROM accounts WHERE NAME LIKE 'AGENT::%' OR NAME LIKE 'CROSS_AGENT::%'");

        // 9. Delete our fake users
        jdbc.update("DELETE FROM users WHERE USERNAME LIKE 'AGENT::%' OR USERNAME LIKE 'CROSS_AGENT::%'");

        // 10. Delete any remaining account_entitlements1 referencing our entitlements (from failed prior runs)
        jdbc.update("""
            DELETE ae FROM account_entitlements1 ae
            INNER JOIN entitlement_values ev ON ae.ENTITLEMENT_VALUEKEY = ev.ENTITLEMENT_VALUEKEY
            WHERE ev.ENTITLEMENTTYPEKEY IN (?, ?)""", ENT_TYPE_AGENT, ENT_TYPE_CAPABILITY);

        // 11. Delete our entitlement_values
        jdbc.update("DELETE FROM entitlement_values WHERE ENTITLEMENTTYPEKEY IN (?, ?)", ENT_TYPE_AGENT, ENT_TYPE_CAPABILITY);

        log.info("Agent data cleared.");
    }

    /** Ensure the agent endpoint and entitlement types exist. */
    public void ensureInfrastructure() {
        int count = jdbc.queryForObject("SELECT COUNT(*) FROM securitysystems WHERE SYSTEMKEY = ?", Integer.class, SECURITY_SYSTEM_KEY);
        if (count == 0) {
            jdbc.update("INSERT INTO securitysystems (SYSTEMKEY, SYSTEMNAME) VALUES (?, 'AgentSystem')", SECURITY_SYSTEM_KEY);
        }
        count = jdbc.queryForObject("SELECT COUNT(*) FROM endpoints WHERE ENDPOINTKEY = ?", Integer.class, ENDPOINT_KEY);
        if (count == 0) {
            jdbc.update("INSERT INTO endpoints (ENDPOINTKEY, ENDPOINTNAME, SECURITYSYSTEMKEY) VALUES (?, 'AgentEndpoint', ?)", ENDPOINT_KEY, SECURITY_SYSTEM_KEY);
        }
        count = jdbc.queryForObject("SELECT COUNT(*) FROM entitlement_types WHERE ENTITLEMENTTYPEKEY = ?", Integer.class, ENT_TYPE_AGENT);
        if (count == 0) {
            jdbc.update("INSERT INTO entitlement_types (ENTITLEMENTTYPEKEY, ENTITLEMENTNAME, ENDPOINTKEY, SYSTEMKEY) VALUES (?, 'Agent', ?, ?)", ENT_TYPE_AGENT, ENDPOINT_KEY, SECURITY_SYSTEM_KEY);
            jdbc.update("INSERT INTO entitlement_types (ENTITLEMENTTYPEKEY, ENTITLEMENTNAME, ENDPOINTKEY, SYSTEMKEY) VALUES (?, 'AgentCapability', ?, ?)", ENT_TYPE_CAPABILITY, ENDPOINT_KEY, SECURITY_SYSTEM_KEY);
        }
    }

    public void insertEntitlementValue(long key, long typeKey, String value) {
        jdbc.update("INSERT INTO entitlement_values (ENTITLEMENT_VALUEKEY, ENTITLEMENTTYPEKEY, ENTITLEMENT_VALUE, ORPHAN) VALUES (?, ?, ?, 0)",
                key, typeKey, value);
    }

    public void insertEntitlements2Edge(long parentKey, long childKey) {
        jdbc.update("INSERT INTO entitlements2 (ENTITLEMENT_VALUE1KEY, ENTITLEMENT_VALUE2KEY) VALUES (?, ?)",
                parentKey, childKey);
    }

    public void insertEntitlementObject(long entKey, long objectKey, long fieldKey, String minValue, String maxValue) {
        jdbc.update("INSERT INTO entitlement_objects (ENTITLEMENT_VALUEKEY, OBJECTKEY, FIELD_KEY, MINVALUE, MXVALUE, OBJECTDELETED) VALUES (?, ?, ?, ?, ?, 0)",
                entKey, objectKey, fieldKey, minValue, maxValue);
    }

    /**
     * Create a user + their self-account (required by user_accounts FK constraint).
     * In Saviynt schema, every user's USERKEY must also exist as an ACCOUNTKEY.
     */
    public void insertUserWithSelfAccount(long userKey, String username) {
        // Insert user
        jdbc.update("INSERT INTO users (USERKEY, USERNAME, ENABLED, PASSWORD) VALUES (?, ?, 1, 'N/A')", userKey, username);
        // Insert self-account (ACCOUNTKEY = USERKEY, satisfies FK83A115DAD2B0B554)
        jdbc.update("INSERT INTO accounts (ACCOUNTKEY, NAME, ENDPOINTKEY, SYSTEMID, STATUS, ORPHAN) VALUES (?, ?, ?, ?, '1', 0)",
                userKey, username, ENDPOINT_KEY, SECURITY_SYSTEM_KEY);
    }

    /**
     * Create an agent account (the actual account that gets entitlement assignments).
     */
    public void insertAgentAccount(long accountKey, String name) {
        jdbc.update("INSERT INTO accounts (ACCOUNTKEY, NAME, ENDPOINTKEY, SYSTEMID, STATUS, ORPHAN) VALUES (?, ?, ?, ?, '1', 0)",
                accountKey, name, ENDPOINT_KEY, SECURITY_SYSTEM_KEY);
    }

    /**
     * Link user to account via user_accounts.
     * Precondition: userKey exists in users AND accounts (self-account pattern).
     */
    public void insertUserAccount(long userKey, long accountKey) {
        jdbc.update("INSERT INTO user_accounts (USERKEY, ACCOUNTKEY) VALUES (?, ?)", userKey, accountKey);
    }

    public void insertAccountEntitlement(long accountKey, long entitlementKey) {
        jdbc.update("INSERT INTO account_entitlements1 (ACCOUNTKEY, ENTITLEMENT_VALUEKEY) VALUES (?, ?)",
                accountKey, entitlementKey);
    }

    /** Look up a user's USERKEY by username pattern (for owner composite linking). */
    public Long findUserKeyByUsername(String usernamePattern) {
        var results = jdbc.queryForList("SELECT USERKEY FROM users WHERE USERNAME LIKE ? LIMIT 1", Long.class, usernamePattern);
        return results.isEmpty() ? null : results.getFirst();
    }
}
