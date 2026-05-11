package com.saviynt.sod.evaluation.model;

import java.util.List;
import java.util.Map;

/**
 * Resolved access picture for a single user.
 * Built during Phase 1 (BFS) and used during Phase 2 (function evaluation).
 */
public record UserAccess(
        long userKey,
        long userIndex,                          // sequential index for BitSet position
        long[] resolvedEntitlements,             // sorted — all reachable ent keys via BFS
        List<AccountAccess> accounts,            // per-account breakdown (for evidence)
        Map<Long, Long> entToDirectAssignment    // resolved ent → which direct assignment provides it (for Phase 4)
) {
    /**
     * Access data for a single account belonging to this user.
     */
    public record AccountAccess(
            long accountKey,
            long endpointKey,
            long[] directAssignments             // what was directly assigned to this account
    ) {}
}
