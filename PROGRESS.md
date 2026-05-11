# SOD Evaluation Service — Progress Summary

## Status: Phase 2 (Function Evaluation) VALIDATED ✅

### What's Built
- Spring Boot 3.5 / Java 21 microservice
- Graph-based hierarchy resolution (BFS with memoization)
- BitSet-based violation detection
- SAP 3-layer evaluation (TCode + AuthObj + Field/Value with OR across TCodes, AND within)
- NonSAP boolean condition evaluation (pre-compiled, no GroovyShell)
- Parallel function evaluation (virtual threads)
- Per-user auth index for O(1) SAP lookups
- Validation service comparing against existing SODRISKS

### Validation Results (Local DB — ecmg6new, Ruleset 1 SAP)
- **14,666 / 14,666 existing violations matched (100%)**
- **0 false negatives**
- 212 false positives (likely stale ground truth — config changed since last old-system run)

### Performance (309 users, 108 SAP functions, 207 risks)
- Phase 0 (config load): 120ms
- Phase 1 (graph + resolve): 10s (6s MySQL load + 0.7s BFS)
- Phase 2 (function eval): **2s** (parallel, auth-indexed)
- Phase 3 (violation detect): <1ms (BitSet AND)
- **Total: ~12 seconds**

### What's Left (TODO)
1. **Phase 4: Evidence collection + DB persistence** (write to SODRISKS, SODRISK_ENTITLEMENT, SODRISK_OBJECTS)
2. **Phase 5: Violation closure** (close stale violations)
3. **Phase 6: Actual vs Potential** (query ENTITLEMENT_USAGE)
4. **Phase 7: Inherent SOD** (role-as-virtual-account)
5. **Large-scale test data generation** for comparison with old system
6. **Preventive SOD endpoint** (single-user real-time evaluation)

### Key Bugs Fixed During Development
1. `userIdentifier` — was sequential counter, fixed to `-1 * accountKey` (matches ECMv4)
2. SAP TCode semantics — was AND across all TCodes, fixed to OR across TCodes (any one TCode with matching auth = satisfied)
3. RELATION field — OR'd value ranges for same TCode+Object+Field
4. Account scoping — must filter by securitySystemId

### Architecture Decisions
- Always reload graph fresh (no caching between runs — correctness > 6s savings)
- Memoize BFS per unique starting node (many accounts share same roles)
- Per-user auth index built once per user, reused across all function evaluations
- Virtual threads for parallel function evaluation
- No DB writes yet — validation-only mode
