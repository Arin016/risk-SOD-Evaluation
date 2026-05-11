package com.saviynt.sod.evaluation.service;

import com.saviynt.sod.evaluation.dao.AccessDataDao;
import com.saviynt.sod.evaluation.model.UserAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Builds the in-memory role hierarchy graph and resolves each user's effective entitlements via BFS.
 *
 * This replaces the current system's hundreds of depth-N self-join SQL queries with:
 * 1. One bulk load of ENTITLEMENTS2 (the graph edges)
 * 2. One bulk load of ACCOUNT_ENTITLEMENTS1 (direct assignments)
 * 3. In-memory BFS per account
 *
 * Memory: O(edges + accounts × avg_resolved_ents)
 * Time: O(edges) for load + O(accounts × avg_reachable_nodes) for BFS
 */
@Service
public class AccessGraphService {

    private static final Logger log = LoggerFactory.getLogger(AccessGraphService.class);

    private final AccessDataDao accessDataDao;

    // The graph: parent → children (adjacency list using primitive arrays for cache efficiency)
    private Map<Long, long[]> graph;
    // Reverse graph: child → parents (for fast ancestor lookup in evidence collection)
    private Map<Long, long[]> reverseGraph;

    public AccessGraphService(AccessDataDao accessDataDao) {
        this.accessDataDao = accessDataDao;
    }

    /**
     * Load the entire ENTITLEMENTS2 table as an adjacency list.
     * Always reloads fresh — ensures correctness after data imports.
     */
    public void loadGraph(Long securitySystemId) {
        log.info("Loading role hierarchy graph from ENTITLEMENTS2...");
        long start = System.currentTimeMillis();

        resolvedCache.clear();

        Map<Long, List<Long>> adjacency = accessDataDao.loadEntitlements2(securitySystemId);

        // Convert List<Long> to long[] for memory efficiency and cache locality
        this.graph = HashMap.newHashMap(adjacency.size());
        adjacency.forEach((parent, children) ->
                this.graph.put(parent, children.stream().mapToLong(Long::longValue).toArray())
        );

        // Build reverse graph: child → parents
        Map<Long, List<Long>> reverseAdj = new HashMap<>();
        adjacency.forEach((parent, children) -> {
            for (long child : children) {
                reverseAdj.computeIfAbsent(child, k -> new ArrayList<>(2)).add(parent);
            }
        });
        this.reverseGraph = HashMap.newHashMap(reverseAdj.size());
        reverseAdj.forEach((child, parents) ->
                this.reverseGraph.put(child, parents.stream().mapToLong(Long::longValue).toArray())
        );

        log.info("Graph loaded: {} parent nodes, {} reverse nodes, took {}ms", graph.size(), reverseGraph.size(), System.currentTimeMillis() - start);
    }

    // Memoization: cache resolved entitlements per unique starting node
    // Many accounts share the same roles — no need to BFS the same role twice
    private final Map<Long, long[]> resolvedCache = new HashMap<>();

    /**
     * Resolve a single account's effective entitlements via BFS from its direct assignments.
     * Uses memoization: if we've already resolved a role, reuse the cached result.
     *
     * @param directAssignments the entitlement keys directly assigned to this account
     * @param maxDepth maximum traversal depth (configurable, up to 14)
     * @return sorted array of all reachable entitlement keys
     */
    public long[] resolveEntitlements(long[] directAssignments, int maxDepth) {
        // Merge resolved sets from each direct assignment (most accounts have 1-15 direct)
        Set<Long> merged = new HashSet<>();
        for (long root : directAssignments) {
            long[] resolved = resolveFromNode(root, maxDepth);
            for (long ent : resolved) merged.add(ent);
        }
        long[] result = merged.stream().mapToLong(Long::longValue).toArray();
        Arrays.sort(result);
        return result;
    }

    /**
     * Resolve all reachable nodes from a single starting node.
     * Cached — if we've already resolved this node, return the cached result.
     */
    /** Get immediate children of a node (1 level only, sorted for binary search). */
    public long[] getImmediateChildren(long parentNode) {
        return graph.get(parentNode); // already sorted long[]
    }

    /** Get immediate parents of a node using reverse graph. */
    public long[] getImmediateParents(long childNode) {
        return reverseGraph != null ? reverseGraph.get(childNode) : null;
    }

    /**
     * Find which of the given direct assignments is an ancestor of targetNode.
     * Uses the already-computed resolvedCache: for each direct assignment,
     * check if targetNode is in its resolved set. O(directAssignments) with O(log n) binary search each.
     */
    public long findAncestorIn(long targetNode, long[] directAssignments) {
        if (directAssignments == null || directAssignments.length == 0) return 0;
        for (long d : directAssignments) {
            if (d == targetNode) return d;
            long[] resolved = resolvedCache.get(d);
            if (resolved != null && java.util.Arrays.binarySearch(resolved, targetNode) >= 0) {
                return d;
            }
        }
        return directAssignments[0]; // fallback
    }

    /** Resolve all reachable nodes from a single starting node (public, for evidence collection). */
    public long[] resolveFromSingleNode(long startNode) {
        return resolveFromNode(startNode, 14);
    }

    private long[] resolveFromNode(long startNode, int maxDepth) {
        long[] cached = resolvedCache.get(startNode);
        if (cached != null) return cached;

        Set<Long> visited = new HashSet<>();
        Deque<long[]> queue = new ArrayDeque<>();
        queue.add(new long[]{startNode, 0});

        while (!queue.isEmpty()) {
            long[] current = queue.poll();
            long node = current[0];
            int depth = (int) current[1];

            if (!visited.add(node)) continue;
            if (depth >= maxDepth) continue;

            long[] children = graph.get(node);
            if (children != null) {
                for (long child : children) {
                    if (!visited.contains(child)) {
                        queue.add(new long[]{child, depth + 1});
                    }
                }
            }
        }

        long[] result = visited.stream().mapToLong(Long::longValue).toArray();
        Arrays.sort(result);
        resolvedCache.put(startNode, result);
        return result;
    }

    public void clearCache() {
        resolvedCache.clear();
    }

    /**
     * Find the path from a direct assignment to a target entitlement.
     * Used during evidence collection (Phase 5) — only called for violators.
     *
     * @return list of node keys from root to target, or empty if not reachable
     */
    public List<Long> findPath(long[] directAssignments, long targetEnt, int maxDepth) {
        // BFS with parent tracking
        Map<Long, Long> parentMap = new HashMap<>();
        Deque<long[]> queue = new ArrayDeque<>();

        for (long root : directAssignments) {
            queue.add(new long[]{root, 0});
            parentMap.put(root, -1L);
        }

        while (!queue.isEmpty()) {
            long[] current = queue.poll();
            long node = current[0];
            int depth = (int) current[1];

            if (node == targetEnt) {
                return reconstructPath(parentMap, targetEnt);
            }

            if (depth >= maxDepth) continue;

            long[] children = graph.get(node);
            if (children != null) {
                for (long child : children) {
                    if (!parentMap.containsKey(child)) {
                        parentMap.put(child, node);
                        queue.add(new long[]{child, depth + 1});
                    }
                }
            }
        }
        return List.of();
    }

    private List<Long> reconstructPath(Map<Long, Long> parentMap, long target) {
        List<Long> path = new ArrayList<>();
        long current = target;
        while (current != -1L) {
            path.add(current);
            current = parentMap.getOrDefault(current, -1L);
        }
        Collections.reverse(path);
        return path;
    }

    public Map<Long, long[]> getGraph() {
        return graph;
    }

    public Map<Long, long[]> getReverseGraph() {
        return reverseGraph;
    }

    public int getResolvedCacheSize() {
        return resolvedCache.size();
    }
}
