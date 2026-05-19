# SOD Evaluation Service — Progress Summary

## Status: Through Phase 4 (persistence to `*_new_job`) + validation ✅

### What's Built
- Spring Boot 3.5 / Java 21 microservice
- Graph-based hierarchy resolution (BFS with memoization)
- **Function-scoped subgraph** via recursive CTE (500M edges → 5M)
- **Auth filtering** by function-referenced (objectKey, fieldKey) pairs (1.2B → 2-5M rows)
- **Per-user auth index caching** (built once, reused across all function evaluations)
- BitSet-based violation detection
- SAP 3-layer evaluation (TCode + AuthObj + Field/Value with OR across TCodes, AND within)
- NonSAP boolean condition evaluation (pre-compiled, no GroovyShell)
- Parallel function evaluation (virtual threads)
- **Excluded edges removed at graph construction** (no runtime exclusion checks)
- **Per-account attribution** — correctly identifies which account caused each violation
- **Full path evidence** stored in PARENTROLEKEYASCSV (complete hierarchy chain)
- **Hash-based delta writes** — only write changed violations (CRC32 fingerprint per violation identity)
- **Stale violation closure** — automatically detects and removes closed violations
- Phase 4: Summary rows to `sodrisks_new_job`, detail rows bulk-loaded via `LOAD DATA LOCAL INFILE`
- Validation service comparing against existing `SODRISKS`
- Supports `SOD_SHARED_VOLUME` env var for server-side LOAD DATA

### Validation Results (Local DB — ecmg6new, Ruleset 1 SAP)
- **14,666 / 14,666 existing violations matched (100%)**
- **0 false negatives**
- 212 false positives (likely stale ground truth — config changed since last old-system run)

### Performance (309 users, 108 SAP functions, 207 risks)
- Phase 0 (config load): 120ms
- Phase 1 (graph + resolve): 3s (subgraph CTE 15ms + MySQL load 2s + BFS 350ms)
- Phase 2 (function eval): **94ms** (parallel, auth-indexed, pre-built per-user)
- Phase 3 (violation detect): <1ms (BitSet AND)
- **Total: ~4 seconds** (excluding Phase 4 persist)

### Performance (91.5K users, 145 functions, 272 risks — Hitachi test profile)
- Phase 1 (graph + resolve): 3s (subgraph 36ms + accounts 2s + resolve 350ms + auth 180ms)
- Phase 2 (function eval): **1.4s** (parallel, pre-built auth indexes)
- Phase 3 (violation detect): 2ms
- Memory peak: 541 MB (1 GB heap)
- **Total: ~5 seconds compute** (+ Phase 4 DB writes)

### POST `/sod-eval/api/v1/evaluate/sync` — end-to-end trace (phases 0–4)

Full URL: `POST http://localhost:9220/sod-eval/api/v1/evaluate/sync` (context path + controller mapping; see [application.yml](src/main/resources/application.yml)).

| Step | Code | What happens |
|------|------|----------------|
| Entry | [EvaluationController.triggerEvaluationSync](src/main/java/com/saviynt/sod/evaluation/controller/EvaluationController.java) | Normalizes null body to empty `EvaluationRequest`; calls `orchestrator.evaluate(request)`; returns `200` with `EvaluationResult`; stores result in `completedJobs`. |
| Phase 0 | [EvaluationOrchestrator.evaluate](src/main/java/com/saviynt/sod/evaluation/service/EvaluationOrchestrator.java) | Resolves `rulesetKeys` (request or `SodConfigDao.loadDefaultRulesetKeys`); loads risks, functions, NonSAP/SAP defs, star tcodes, preceding-zeros flag, excluded ent pairs, TCD mappings, `allowedTcodesPerFunc`, etc. |
| Phase 1 | Same | `accessGraph.loadGraph(securitySystemId)`; `AccessDataDao` loads assignments, metadata, role auth; `resolveAllUsers` groups accounts by user, BFS-merge entitlements into `List<UserAccess>`. |
| Phase 2 | Same | Virtual-thread pool: each NonSAP → one BitSet (`funcKey###0`); each SAP endpoint → BitSet (`funcKey###ep`) + `evidenceMap` entries for detail rows. |
| Phase 3 | Same | `ViolationDetectionService.detectViolations` → `Map "riskId###endpointKey" → BitSet`. |
| Phase 4 | `persistViolations` | Deletes prior `sodrisks_new_job` / `sodrisk_entitlement_new_job` rows for ruleset scope; batch INSERT summaries; maps `SODKEY`s; writes TSV to temp file; `LOAD DATA LOCAL INFILE` into `sodrisk_entitlement_new_job`. |
| After Phase 4 | Same method | `ValidationService.validate` vs legacy `SODRISKS`; JDBC checksum queries on old vs new detail tables. |

**Note:** `GET /api/v1/status` returns the last completed run payload; there is no `/status/{jobId}` path (async `POST /evaluate` also does not return a job id in the accepted body).

### What's Left (TODO)
1. **Production parity** — optional writes to primary `SODRISKS` / `SODRISK_ENTITLEMENT` / `SODRISK_OBJECTS` if required beyond side-by-side `*_new_job` tables.
2. **Phase 6: Actual vs Potential** (query `ENTITLEMENT_USAGE`)
3. **Phase 7: Inherent SOD** (role-as-virtual-account)
4. **Large-scale test data generation** (500K accounts) for Hitachi-scale verification
5. **Preventive SOD endpoint** (single-user real-time evaluation)
6. **SOD_NONSAP_ORG_CALCULATION** — Oracle EBS org-scoped NonSAP (not implemented)

### Key Bugs Fixed During Development
1. `userIdentifier` — was sequential counter, fixed to `-1 * accountKey` (matches ECMv4)
2. SAP TCode semantics — was AND across all TCodes, fixed to OR across TCodes (any one TCode with matching auth = satisfied)
3. RELATION field — OR'd value ranges for same TCode+Object+Field
4. Account scoping — must filter by securitySystemId

### Architecture Decisions
- Always reload graph fresh (no caching between runs — correctness > 6s savings)
- Function-scoped subgraph: only load edges that are ancestors of function-referenced tcodes/entitlements (recursive CTE)
- Auth filtering: only load entitlement_objects for (objectKey, fieldKey) pairs referenced by functions
- Per-user auth index built once per user, reused across all function evaluations
- Excluded edges removed from graph at construction (not checked during evaluation)
- Memoize BFS per unique starting node (many accounts share same roles)
- Per-user auth index built once per user, reused across all function evaluations
- Virtual threads for parallel function evaluation
- Evidence during Phase 2; Phase 4 is bulk I/O to `*_new_job` tables for safe comparison with legacy data
- Full path evidence stored in PARENTROLEKEYASCSV (complete chain from direct assignment to target)
- Per-account attribution: each account checked independently for violation causation
