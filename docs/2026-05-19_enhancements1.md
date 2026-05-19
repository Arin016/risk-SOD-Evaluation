# SOD Evaluation Service — Enhancements (2026-05-19)

## Context

Optimizations and correctness fixes to handle Hitachi production scale:
- 300-500K accounts
- 400-500M entitlements2 edges
- 1.2B entitlement_objects rows
- 350K violations, 250M detail rows

Discussion on scaling bottlenecks and evidence correctness.

---

## Performance Optimizations

### P5: SecuritySystemId Required (fail-fast)

**Problem:** If `securitySystemId` was null, the code loaded the ENTIRE `entitlements2` (500M rows) and `entitlement_objects` (1.2B rows) into memory — instant OOM.

**Fix:** Made `securitySystemId` a required parameter. Null throws `IllegalArgumentException` immediately. Deprecated the no-arg overloads.

**Files:** `AccessDataDao.java`, `EvaluationOrchestrator.java`

---

### P2: Auth Filtering by (objectKey, fieldKey)

**Problem:** Loading all 1.2B `entitlement_objects` rows into memory = 60 GB. Most are irrelevant — functions only reference ~200 distinct (objectKey, fieldKey) pairs.

**Fix:** Extract all (objectKey, fieldKey) pairs from `SAPFunctionDefs` in Phase 0. Pass to `loadEntitlementObjects()` which adds a SQL WHERE clause filtering to only those pairs.

**Impact:** 1.2B rows → ~2-5M rows loaded. 60 GB → ~200 MB.

**Files:** `AccessDataDao.java`, `EvaluationOrchestrator.java`

---

### P3: Per-User Auth Index Caching

**Problem:** `buildUserAuthIndex()` was called inside `evaluateSAPWithEvidence()` for every user × every function. At 500K users × 145 functions = 72.5M HashMap constructions.

**Fix:** Pre-build auth index once per user before Phase 2. Store as `List<Map<Long, List<AuthEntry>>>` indexed by user position. Pass to evaluation via new overload.

**Impact:** 72.5M index builds → 500K (once per user). Phase 2 went from 27 sec → 1.4 sec on 91.5K users.

**Files:** `FunctionEvaluationService.java`, `EvaluationOrchestrator.java`

---

### P4: Function-Scoped Subgraph (Recursive CTE)

**Problem:** Loading all 500M `entitlements2` edges into memory = 8 GB. Most edges are irrelevant — only edges that are ancestors of function-referenced tcodes/entitlements matter.

**Fix:** 
1. Collect all leaf nodes from functions (tcode keys from SAP + entitlement keys from NonSAP + star tcodes)
2. Run recursive CTE in MySQL to walk UP from leaf nodes, finding all ancestor nodes
3. Load only edges where parent is in the ancestor set

**Impact:** 500M edges → ~5M edges. 8 GB → ~80 MB.

**Safety:** No regression for SAP because auth is always on depth-1/depth-2 roles which ARE ancestors of function tcodes (SAP max hierarchy depth = 2). Verified FP=0, FN=0 on system 5.

**Files:** `AccessDataDao.java`, `AccessGraphService.java`, `EvaluationOrchestrator.java`

---

## Correctness Fixes

### C1: Exclusion Per-Path (Graph-Level)

**Problem:** Old system checks exclusion per-row (per-path). If user has path A→B→C (B excluded) AND path A→X→C (clean), old system only skips the excluded path — user still satisfies via clean path. Our code blanket-excluded the user.

**Fix:** Remove excluded edges from the graph during construction. BFS naturally can't traverse excluded paths. If a clean alternate path exists, BFS finds it. No separate exclusion check needed in evaluation.

**Impact:** Correct behavior for users with both excluded and clean paths to the same entitlement. Removed `isExcludedByEntType` check from `evaluateNonSAP` (redundant — graph handles it).

**Files:** `AccessGraphService.java`, `EvaluationOrchestrator.java`, `FunctionEvaluationService.java`

---

### C3: Per-Account Attribution

**Problem:** `collectSAPEvidence` used `user.accounts().getFirst()` — always blamed the first account even if a different account caused the violation.

**Fix:** Iterate ALL accounts in `collectSAPEvidence`. For each account, check which direct assignments reach the tcode's parent role. Only write evidence for accounts that actually provide the violating path.

**Impact:** Multi-account users now correctly show which specific account caused each violation.

**Files:** `FunctionEvaluationService.java`

---

### C2: Full Path Evidence

**Problem:** `PARENTROLEKEYASCSV` stored only the direct assignment key (same as `ASSOCIATEDSAPROLEKEY` for flat hierarchies). For deep hierarchies, the intermediate nodes were lost.

**Fix:** For NonSAP evidence, call `accessGraph.findPath(directs, funcEntKey, maxDepth)` to get the complete chain. Store as CSV: `"directRole,intermediate1,...,immediateParent"`.

**Impact:** Full audit trail showing exactly how a violation occurred through the hierarchy. Verified with S25 test scenario (4-level deep NonSAP): `PARENTROLEKEYASCSV = "RoleTop,RoleMid1,RoleMid2,RoleBottom"`.

**Files:** `EvaluationOrchestrator.java`

---

## Test Scenario Added

### S25: Deep NonSAP Path Evidence (4 levels)

Tests full path storage for deep NonSAP hierarchies:
- Hierarchy: RoleTop → RoleMid1 → RoleMid2 → RoleBottom → EntitlementX
- Verifies `PARENTROLEKEYASCSV` contains the complete chain
- Added to `ScenarioBuilder.java` and `TestDataGenerator.java`

---

## Verification Results

| System | Violations | FP | FN | Status |
|--------|-----------|----|----|--------|
| System 5 (real SAP, 309 users) | 14,806 | 0 | 0 | ✅ |
| System 200 (test data, 91.5K users) | 58,500 | — | — | ✅ (no baseline) |
| System 68 (EBS NonSAP, 10 users) | 10 | 0 | 0 | ✅ |
| System 200 small (19.2K users) | 12,300 | — | — | ✅ |

---

## Performance Comparison (System 200, 91.5K users)

| Metric | Before | After | Improvement |
|--------|--------|-------|-------------|
| Phase 1 (graph + resolve) | 32 sec | 3 sec | **10x** |
| Phase 2 (evaluate 145 functions) | 27 sec | 1.4 sec | **19x** |
| Phase 3 (detect violations) | <1 ms | <1 ms | same |
| Memory peak | 858 MB | 541 MB | **37% less** |
| Graph nodes loaded | ~5,000 | 156 | **32x less** |
| Auth rows loaded | 505K | 5,091 roles | **filtered** |

---

## Projected Impact at Hitachi Scale (500K accounts)

| Resource | Before (naive) | After (optimized) |
|----------|---------------|-------------------|
| Graph memory | 8 GB (500M edges) | ~80 MB (5M subgraph) |
| Auth memory | 60 GB (1.2B rows) | ~200 MB (filtered) |
| Auth index builds | 72.5M | 500K (once per user) |
| Total heap needed | 68+ GB | ~2-3 GB |
| Estimated wall clock | hours | ~5-10 min |

---

### P1: Hash-Based Delta Writes

**Problem:** Every run deleted ALL detail rows (250M at Hitachi scale) then re-inserted them. The delete alone took 6-7 hours in the old system.

**Fix:** 
1. Load existing content hashes per violation identity `(USERIDENTIFIER, RISKKEY, ENDPOINTKEY)` via `GROUP BY` with `COUNT(*) + SUM(CRC32(...))`
2. Compute new hashes while generating detail rows
3. Diff: unchanged (skip), new (insert), stale (delete), changed (delete + insert)
4. SODKEYs kept stable across runs (no delete+recreate of `sodrisks_new_job`)
5. Only write detail rows for new/changed violations via LOAD DATA
6. Supports `SOD_SHARED_VOLUME` env var for server-side LOAD DATA (no LOCAL needed)

**Impact:**
- Nothing changed between runs: **0 rows written, ~135ms** (just hash comparison)
- 1% changed: only ~2.5M rows written instead of 250M
- Stale violations automatically detected and cleaned up

**Verified:**
- Run 1 (existing data matches): `14806 unchanged, 0 to insert, 0 to delete` → 135ms
- Run 2 (deleted account's roles): `14601 unchanged, 0 to insert, 205 to delete` → correctly closed 205 stale violations

**Files:** `EvaluationOrchestrator.java`

---

## Test Scenario Added

### S25: Deep NonSAP Path Evidence (4 levels)

Tests full path storage for deep NonSAP hierarchies:
- Hierarchy: RoleTop → RoleMid1 → RoleMid2 → RoleBottom → EntitlementX
- Verifies `PARENTROLEKEYASCSV` contains the complete chain: `"RoleTop,RoleMid1,RoleMid2,RoleBottom"`
- Added to `ScenarioBuilder.java` and `TestDataGenerator.java`

---

## All Tasks Complete

Every optimization and correctness fix from the design discussion has been implemented and verified:
- P5 ✅, P2 ✅, P3 ✅, P4 ✅, P1 ✅ (performance)
- C1 ✅, C2 ✅, C3 ✅ (correctness)
