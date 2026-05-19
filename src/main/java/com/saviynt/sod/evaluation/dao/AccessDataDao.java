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
     * securitySystemId is REQUIRED — loading without system scope would pull 500M+ rows and OOM.
     */
    public Map<Long, List<Long>> loadEntitlements2(Long securitySystemId) {
        if (securitySystemId == null) {
            throw new IllegalArgumentException("securitySystemId is required for loadEntitlements2 — unscoped load would exhaust memory");
        }
        log.info("Loading ENTITLEMENTS2 for system {}...", securitySystemId);
        Map<Long, List<Long>> graph = new HashMap<>(30_000);

        String sql = """
                SELECT e2.ENTITLEMENT_VALUE1KEY, e2.ENTITLEMENT_VALUE2KEY
                FROM entitlements2 e2
                JOIN entitlement_values ev ON e2.ENTITLEMENT_VALUE1KEY = ev.ENTITLEMENT_VALUEKEY
                JOIN entitlement_types et ON ev.ENTITLEMENTTYPEKEY = et.ENTITLEMENTTYPEKEY
                JOIN endpoints ep ON et.ENDPOINTKEY = ep.ENDPOINTKEY
                WHERE ep.SECURITYSYSTEMKEY = """ + securitySystemId;

        jdbc.query(sql, rs -> {
            long parent = rs.getLong(1);
            long child = rs.getLong(2);
            graph.computeIfAbsent(parent, k -> new ArrayList<>(4)).add(child);
        });

        log.info("Loaded {} parent nodes from ENTITLEMENTS2", graph.size());
        return graph;
    }

    /** @deprecated Use loadEntitlements2(securitySystemId) — system scope is required */
    @Deprecated
    public Map<Long, List<Long>> loadEntitlements2() {
        throw new UnsupportedOperationException("securitySystemId is required — use loadEntitlements2(Long)");
    }

    /**
     * Load ONLY the subgraph of entitlements2 that is relevant to the given function leaf nodes.
     * Uses a recursive CTE to walk UP from leaf nodes (tcodes/entitlements referenced by functions)
     * to find all ancestor nodes, then loads only edges within that ancestor set.
     *
     * At Hitachi scale: 500M total edges → ~1-5M relevant edges (8 GB → 80 MB).
     */
    public Map<Long, List<Long>> loadEntitlements2Subgraph(Long securitySystemId, Set<Long> functionLeafNodes) {
        if (securitySystemId == null) {
            throw new IllegalArgumentException("securitySystemId is required");
        }
        if (functionLeafNodes == null || functionLeafNodes.isEmpty()) {
            log.warn("No function leaf nodes provided — falling back to full graph load");
            return loadEntitlements2(securitySystemId);
        }

        log.info("Loading ENTITLEMENTS2 subgraph for system {} (starting from {} leaf nodes)...", securitySystemId, functionLeafNodes.size());
        long start = System.currentTimeMillis();

        // Step 1: Find all ancestor nodes via recursive CTE
        // Walk UP from function leaf nodes through the hierarchy
        String leafCsv = functionLeafNodes.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));

        String cteSql = """
                WITH RECURSIVE ancestors AS (
                    SELECT DISTINCT e2.ENTITLEMENT_VALUE1KEY AS node
                    FROM entitlements2 e2
                    JOIN entitlement_values ev ON e2.ENTITLEMENT_VALUE1KEY = ev.ENTITLEMENT_VALUEKEY
                    JOIN entitlement_types et ON ev.ENTITLEMENTTYPEKEY = et.ENTITLEMENTTYPEKEY
                    JOIN endpoints ep ON et.ENDPOINTKEY = ep.ENDPOINTKEY
                    WHERE ep.SECURITYSYSTEMKEY = %d
                      AND e2.ENTITLEMENT_VALUE2KEY IN (%s)
                    UNION
                    SELECT e2.ENTITLEMENT_VALUE1KEY
                    FROM entitlements2 e2
                    JOIN ancestors a ON e2.ENTITLEMENT_VALUE2KEY = a.node
                    JOIN entitlement_values ev ON e2.ENTITLEMENT_VALUE1KEY = ev.ENTITLEMENT_VALUEKEY
                    JOIN entitlement_types et ON ev.ENTITLEMENTTYPEKEY = et.ENTITLEMENTTYPEKEY
                    JOIN endpoints ep ON et.ENDPOINTKEY = ep.ENDPOINTKEY
                    WHERE ep.SECURITYSYSTEMKEY = %d
                )
                SELECT node FROM ancestors
                """.formatted(securitySystemId, leafCsv, securitySystemId);

        Set<Long> relevantNodes = new HashSet<>(functionLeafNodes);
        jdbc.query(cteSql, rs -> { relevantNodes.add(rs.getLong(1)); });
        log.info("Recursive CTE found {} relevant nodes (from {} leaf nodes) in {}ms",
                relevantNodes.size(), functionLeafNodes.size(), System.currentTimeMillis() - start);

        // Step 2: Load only edges where parent is in the relevant set
        // (child will be relevant too since we walked up from leaves)
        String relevantCsv = relevantNodes.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
        String edgeSql = """
                SELECT e2.ENTITLEMENT_VALUE1KEY, e2.ENTITLEMENT_VALUE2KEY
                FROM entitlements2 e2
                JOIN entitlement_values ev ON e2.ENTITLEMENT_VALUE1KEY = ev.ENTITLEMENT_VALUEKEY
                JOIN entitlement_types et ON ev.ENTITLEMENTTYPEKEY = et.ENTITLEMENTTYPEKEY
                JOIN endpoints ep ON et.ENDPOINTKEY = ep.ENDPOINTKEY
                WHERE ep.SECURITYSYSTEMKEY = %d
                  AND e2.ENTITLEMENT_VALUE1KEY IN (%s)
                """.formatted(securitySystemId, relevantCsv);

        Map<Long, List<Long>> graph = new HashMap<>(relevantNodes.size());
        jdbc.query(edgeSql, rs -> {
            long parent = rs.getLong(1);
            long child = rs.getLong(2);
            graph.computeIfAbsent(parent, k -> new ArrayList<>(4)).add(child);
        });

        log.info("Loaded subgraph: {} parent nodes, {}ms total", graph.size(), System.currentTimeMillis() - start);
        return graph;
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
     * securitySystemId is REQUIRED — loading without system scope would pull 1.2B+ rows and OOM.
     * Optionally filtered by (objectKey, fieldKey) pairs to avoid loading irrelevant auth.
     */
    public Map<Long, List<AuthEntry>> loadEntitlementObjects(Long securitySystemId) {
        return loadEntitlementObjects(securitySystemId, null);
    }

    /**
     * Load SAP auth data filtered to only (objectKey, fieldKey) pairs referenced by functions.
     * At Hitachi scale: 1.2B total rows → ~2-5M relevant rows (200 MB vs 60 GB).
     */
    public Map<Long, List<AuthEntry>> loadEntitlementObjects(Long securitySystemId, Set<Long> relevantObjFieldKeys) {
        if (securitySystemId == null) {
            throw new IllegalArgumentException("securitySystemId is required for loadEntitlementObjects — unscoped load would exhaust memory");
        }
        log.info("Loading ENTITLEMENT_OBJECTS for system {}{}...", securitySystemId,
                relevantObjFieldKeys != null ? " (filtered to " + relevantObjFieldKeys.size() + " obj/field pairs)" : " (unfiltered)");

        String sql = """
                SELECT eo.ENTITLEMENT_VALUEKEY, eo.OBJECTKEY, eo.FIELD_KEY, eo.MINVALUE, eo.MXVALUE
                FROM entitlement_objects eo
                JOIN entitlement_values ev ON eo.ENTITLEMENT_VALUEKEY = ev.ENTITLEMENT_VALUEKEY
                JOIN entitlement_types et ON ev.ENTITLEMENTTYPEKEY = et.ENTITLEMENTTYPEKEY
                JOIN endpoints ep ON et.ENDPOINTKEY = ep.ENDPOINTKEY
                WHERE (eo.objectdeleted = 0 OR eo.objectdeleted IS NULL)
                AND ep.SECURITYSYSTEMKEY = """ + securitySystemId;

        // Filter by relevant (objectKey, fieldKey) pairs — avoids loading 1.2B irrelevant rows
        if (relevantObjFieldKeys != null && !relevantObjFieldKeys.isEmpty()) {
            // Build (OBJECTKEY, FIELD_KEY) IN clause using composite key decomposition
            // compositeKey = objectKey * 100000 + fieldKey
            StringBuilder filter = new StringBuilder(" AND (");
            boolean first = true;
            for (long compositeKey : relevantObjFieldKeys) {
                long objKey = compositeKey / 100000L;
                long fldKey = compositeKey % 100000L;
                if (!first) filter.append(" OR ");
                filter.append("(eo.OBJECTKEY=").append(objKey).append(" AND eo.FIELD_KEY=").append(fldKey).append(")");
                first = false;
            }
            filter.append(")");
            sql += filter.toString();
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

    /** @deprecated Use loadEntitlementObjects(securitySystemId) — system scope is required */
    @Deprecated
    public Map<Long, List<AuthEntry>> loadEntitlementObjects() {
        throw new UnsupportedOperationException("securitySystemId is required — use loadEntitlementObjects(Long)");
    }
}
