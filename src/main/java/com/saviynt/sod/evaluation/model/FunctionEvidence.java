package com.saviynt.sod.evaluation.model;

import java.util.List;

/**
 * Evidence of WHY a user satisfies a function.
 * Collected during Phase 2 evaluation, consumed during Phase 4 persistence.
 */
public record FunctionEvidence(
        long accountKey,
        long tcodeKey,
        long assocSapRole,   // immediate parent of tcode in user's resolved ents
        long directRole,     // direct assignment that reaches assocSapRole
        long endpointKey     // which endpoint this evidence is for (0 for non-SAPGROUP)
) {}
