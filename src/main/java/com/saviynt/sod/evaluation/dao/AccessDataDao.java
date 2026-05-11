package com.saviynt.sod.evaluation.dao;

import com.saviynt.sod.evaluation.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.*;

/**
 * Data access for the core access tables: ENTITLEMENTS2, ACCOUNT_ENTITLEMENTS1, ENTITLEMENT_OBJECTS.
 * Uses raw JDBC for maximum performance — no ORM overhead.
 */
@Repository
public class AccessDataDao {

    private static final Logger log = LoggerFactory.getLogger(AccessDataDao.class);
    private final JdbcTemplate jdbc;

    public AccessDataDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public javax.sql.DataSource getDataSource() {
        return jdbc.getDataSource();
    }

    /**
     * Load role hierarchy graph scoped to a security system's endpoints.
     * Only loads edges where the parent belongs to the system's entitlement types.
     */
    public Map<Long, List<Long>> loadEntitlements2(Long securitySystemId) {
        log.info("Loading ENTITLEMENTS2...");
        Map<Long, List<Long>> graph = new HashMap<>(30_000);

        String sql;
        if (securitySystemId != null) {
            sql = """
                SELECT e2.ENTITLEMENT_VALUE1KEY, e2.ENTITLEMENT_VALUE2KEY
                FROM entitlements2 e2
                JOIN entitlement_values ev ON e2.ENTITLEMENT_VALUE1KEY = ev.ENTITLEMENT_VALUEKEY
                JOIN entitlement_types et ON ev.ENTITLEMENTTYPEKEY = et.ENTITLEMENTTYPEKEY
                JOIN endpoints ep ON et.ENDPOINTKEY = ep.ENDPOINTKEY
                WHERE ep.SECURITYSYSTEMKEY = """ + securitySystemId;
        } else {
            sql = "SELECT ENTITLEMENT_VALUE1KEY, ENTITLEMENT_VALUE2KEY FROM entitlements2";
        }

        jdbc.query(sql, rs -> {
            long parent = rs.getLong(1);
            long child = rs.getLong(2);
            graph.computeIfAbsent(parent, k -> new ArrayList<>(4)).add(child);
        });

        log.info("Loaded {} parent nodes from ENTITLEMENTS2", graph.size());
        return graph;
    }

    /** Backward-compatible overload */
    public Map<Long, List<Long>> loadEntitlements2() {
        return loadEntitlements2(null);
    }

    /**
     * Load direct account→entitlement assignments.
     * Returns: accountKey → list of directly assigned entitlement keys.
     */
    public Map<Long, List<Long>> loadAccountEntitlements(String accountFilter) {
        return loadAccountEntitlements(accountFilter, null);
    }

    public Map<Long, List<Long>> loadAccountEntitlements(String accountFilter, String entQuery) {
        log.info("Loading ACCOUNT_ENTITLEMENTS1...");
        String sql = """
                SELECT ae.ACCOUNTKEY, ae.ENTITLEMENT_VALUEKEY
                FROM account_entitlements1 ae
                JOIN accounts a ON a.ACCOUNTKEY = ae.ACCOUNTKEY
                JOIN entitlement_values ev ON ae.ENTITLEMENT_VALUEKEY = ev.ENTITLEMENT_VALUEKEY
                WHERE a.STATUS <> 'SUSPENDED FROM IMPORT SERVICE'
                """ + (accountFilter != null ? " AND " + accountFilter : "")
                    + (entQuery != null && !entQuery.isBlank() ? " " + entQuery : "");

        Map<Long, List<Long>> assignments = new HashMap<>(50_000);

        jdbc.query(sql, rs -> {
            long accountKey = rs.getLong(1);
            long entKey = rs.getLong(2);
            assignments.computeIfAbsent(accountKey, k -> new ArrayList<>(8)).add(entKey);
        });

        log.info("Loaded assignments for {} accounts", assignments.size());
        return assignments;
    }

    /**
     * Load account metadata: accountKey → {userKey, endpointKey, systemId}.
     */
    public Map<Long, long[]> loadAccountMetadata(String accountFilter) {
        String sql = """
                SELECT a.ACCOUNTKEY, COALESCE(ua.USERKEY, 0), a.ENDPOINTKEY, a.SYSTEMID
                FROM accounts a
                LEFT JOIN user_accounts ua ON ua.ACCOUNTKEY = a.ACCOUNTKEY
                WHERE a.STATUS <> 'SUSPENDED FROM IMPORT SERVICE'
                """ + (accountFilter != null ? " AND " + accountFilter : "");

        Map<Long, long[]> metadata = new HashMap<>(50_000);

        jdbc.query(sql, rs -> {
            long accountKey = rs.getLong(1);
            long userKey = rs.getLong(2);
            long endpointKey = rs.getLong(3);
            long systemId = rs.getLong(4);
            metadata.put(accountKey, new long[]{userKey, endpointKey, systemId});
        });

        log.info("Loaded metadata for {} accounts", metadata.size());
        return metadata;
    }

    /**
     * Load SAP auth data scoped to a security system's endpoints.
     * Only loads auth for roles belonging to the system's entitlement types.
     */
    public Map<Long, List<AuthEntry>> loadEntitlementObjects(Long securitySystemId) {
        log.info("Loading ENTITLEMENT_OBJECTS...");
        String sql;
        if (securitySystemId != null) {
            sql = """
                SELECT eo.ENTITLEMENT_VALUEKEY, eo.OBJECTKEY, eo.FIELD_KEY, eo.MINVALUE, eo.MXVALUE
                FROM entitlement_objects eo
                JOIN entitlement_values ev ON eo.ENTITLEMENT_VALUEKEY = ev.ENTITLEMENT_VALUEKEY
                JOIN entitlement_types et ON ev.ENTITLEMENTTYPEKEY = et.ENTITLEMENTTYPEKEY
                JOIN endpoints ep ON et.ENDPOINTKEY = ep.ENDPOINTKEY
                WHERE (eo.objectdeleted = 0 OR eo.objectdeleted IS NULL)
                AND ep.SECURITYSYSTEMKEY = """ + securitySystemId;
        } else {
            sql = """
                SELECT ENTITLEMENT_VALUEKEY, OBJECTKEY, FIELD_KEY, MINVALUE, MXVALUE
                FROM entitlement_objects
                WHERE (objectdeleted = 0 OR objectdeleted IS NULL)
                """;
        }

        Map<Long, List<AuthEntry>> roleAuth = new HashMap<>(50_000);

        jdbc.query(sql, rs -> {
            long roleKey = rs.getLong(1);
            long objectKey = rs.getLong(2);
            long fieldKey = rs.getLong(3);
            String minValue = rs.getString(4);
            String maxValue = rs.getString(5);
            roleAuth.computeIfAbsent(roleKey, k -> new ArrayList<>(4))
                    .add(new AuthEntry(roleKey, objectKey, fieldKey, minValue, maxValue));
        });

        log.info("Loaded auth entries for {} roles", roleAuth.size());
        return roleAuth;
    }

    /** Backward-compatible overload */
    public Map<Long, List<AuthEntry>> loadEntitlementObjects() {
        return loadEntitlementObjects(null);
    }
}
