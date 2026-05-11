# SOD Evaluation Microservice — Code Walkthrough

## Overview

This document provides a file-by-file explanation of every source file in the SOD Evaluation Microservice. The project is a from-scratch replacement for the legacy ECMv4 Groovy-based SOD evaluation engine (7,635 lines). The new system is written in Java 21 with Spring Boot 3.5, using raw JDBC (no ORM) for maximum performance.

**Total source files:** 20 Java files across 5 packages.

---

## Package Structure

```
com.saviynt.sod.evaluation/
├── Application.java                    # Spring Boot entry point
├── controller/
│   └── EvaluationController.java       # REST API layer
├── dto/
│   ├── EvaluationRequest.java          # Inbound request DTO
│   └── EvaluationResult.java           # Outbound response DTO
├── service/
│   ├── EvaluationOrchestrator.java     # Pipeline coordinator (Phase 0-5)
│   ├── AccessGraphService.java         # Graph loading + BFS resolution
│   ├── FunctionEvaluationService.java  # Function → BitSet evaluation
│   ├── ViolationDetectionService.java  # BitSet AND → violations
│   ├── ValidationService.java          # Correctness verification vs old system
│   └── ValueMatcher.java              # SAP auth value matching logic
├── dao/
│   ├── SodConfigDao.java              # Config data access (risks, functions, conditions)
│   └── AccessDataDao.java             # Access data access (graph, assignments, auth)
└── model/
    ├── Risk.java                       # Risk definition (2-5 functions)
    ├── SodFunction.java               # Function metadata + type enum
    ├── SAPFunctionDef.java            # SAP function conditions (grouped)
    ├── NonSAPCondition.java           # Compiled boolean condition tree
    ├── AuthEntry.java                 # Single auth object/field/value row
    ├── UserAccess.java                # Resolved user access picture
    ├── FunctionEvidence.java          # Evidence of why a user satisfies a function
    └── Violation.java                 # Detected violation with evidence
```

---

## Dependency Graph

```
EvaluationController
    └── EvaluationOrchestrator
            ├── AccessGraphService ──────── AccessDataDao
            ├── FunctionEvaluationService ── ValueMatcher
            ├── ViolationDetectionService
            ├── ValidationService
            ├── SodConfigDao
            └── AccessDataDao
```

All services are Spring-managed singletons injected via constructor injection. There is no circular dependency.

---

## File-by-File Explanation

---

### 1. `Application.java`

**Package:** `com.saviynt.sod.evaluation`  
**Purpose:** Spring Boot application entry point.  
**Key Methods:** `main(String[] args)` — bootstraps the Spring context.  
**Design Decision:** Minimal — just `@SpringBootApplication` with no custom configuration beans. All configuration is in `application.properties` / `application.yml`.

---

### 2. `EvaluationController.java`

**Package:** `com.saviynt.sod.evaluation.controller`  
**Purpose:** REST API layer exposing SOD evaluation triggers and status queries.  
**How it fits:** Entry point for all external requests. Delegates immediately to `EvaluationOrchestrator`.

**Key Methods:**

| Method | Endpoint | Purpose |
|--------|----------|---------|
| `triggerEvaluation()` | `POST /api/v1/evaluate` | Async evaluation — returns immediately, runs in background |
| `triggerEvaluationSync()` | `POST /api/v1/evaluate/sync` | Synchronous evaluation — blocks until complete |
| `getStatus()` | `GET /api/v1/status` | Returns current/last run status |
| `health()` | `GET /api/v1/health` | Health check |

**Design Decisions:**
- Async endpoint uses `CompletableFuture.runAsync()` for non-blocking trigger
- Tracks `currentRun` as volatile field for simple concurrency (single-run-at-a-time model)
- Returns HTTP 409 if evaluation already in progress
- `completedJobs` ConcurrentHashMap stores historical results by jobId

---

### 3. `EvaluationRequest.java`

**Package:** `com.saviynt.sod.evaluation.dto`  
**Purpose:** Inbound request payload for triggering evaluation.  
**Fields:**
- `rulesetKeys` (List<Long>) — which rulesets to evaluate (empty = use defaults)
- `securitySystemId` (Long) — scope to a specific security system
- `accountQuery` (String) — optional SQL WHERE clause to filter accounts
- `entitlementQuery` (String) — optional SQL fragment to filter entitlements

**Design Decision:** Java record for immutability. Compact constructor normalizes null `rulesetKeys` to empty list.

---

### 4. `EvaluationResult.java`

**Package:** `com.saviynt.sod.evaluation.dto`  
**Purpose:** Outbound response summarizing a completed evaluation run.  
**Fields:** jobId, status, totalAccounts, totalUsers, functionsEvaluated, risksEvaluated, violationsOpened, violationsClosed, violationsAccepted, startTime, endTime, duration, error.

**Key Methods:**
- `success(...)` — factory for successful runs
- `failure(...)` — factory for failed runs

**Design Decision:** Record with static factories. Duration computed from start/end timestamps.

---

### 5. `EvaluationOrchestrator.java`

**Package:** `com.saviynt.sod.evaluation.service`  
**Purpose:** The central coordinator that executes the full 5-phase evaluation pipeline.  
**How it fits:** Called by the controller. Orchestrates all other services in sequence.

**Key Methods:**

| Method | Purpose |
|--------|---------|
| `evaluate(EvaluationRequest)` | Runs the full pipeline, returns EvaluationResult |
| `resolveAllUsers(...)` | Groups accounts by user, BFS-resolves entitlements per user |
| `persistViolations(...)` | Phase 4: writes summary + detail rows to DB |
| `extractUniqueFunctionKeys(...)` | Collects all function keys referenced by risks |
| `filterByType(...)` | Separates SAP vs NonSAP function keys |
| `buildAccountFilter(...)` | Constructs SQL WHERE clause from request params |
| `getFunctionEntitlementKeys(...)` | Cached lookup of NonSAP function's entitlement keys |

**Pipeline Phases (all in `evaluate()`):**

1. **Phase 0 — Load Config:** Loads risks, functions, SAP/NonSAP conditions, star tcode keys, `considerPrecedingZeros` config, excluded entitlement pairs, TCD-field resolved tcodes, and pre-computes `allowedTcodesPerFunc` (old system's sequential tcode-evaluated behavior).

2. **Phase 1 — Load Graph + Resolve:** Loads the role hierarchy graph, direct account assignments, account metadata, and auth entries. Then calls `resolveAllUsers()` which groups accounts by user (via `user_accounts` table) and BFS-resolves each user's effective entitlements.

3. **Phase 2 — Evaluate Functions (Parallel):** Creates a virtual-thread executor. Submits one task per function (NonSAP or SAP×endpoint). Each task produces a BitSet and optionally collects evidence. All tasks run in parallel.

4. **Phase 3 — Detect Violations:** Calls `ViolationDetectionService.detectViolations()` which ANDs function BitSets per risk.

5. **Phase 4 — Persist:** Clears previous run data, writes summary rows via batch INSERT, fetches generated SODKEYs, writes detail rows to a temp CSV file, then bulk-loads via `LOAD DATA LOCAL INFILE`.

6. **Validation:** Compares results against old system's `sodrisks` table. Also performs CRC32 checksum comparison on detail rows.

**Design Decisions:**
- Virtual threads for parallel function evaluation (Java 21 feature)
- Evidence collected during Phase 2 (zero extra computation in Phase 4)
- `allowedTcodesPerFunc` replicates old system's sequential state: a tcode is only "allowed" for the first function that contains it (processed in function ID order)
- `LOAD DATA LOCAL INFILE` for bulk detail row writes (10-50x faster than batch INSERT)
- User grouping: unmapped accounts get `userKey = -1 * accountKey` (matches ECMv4 behavior)
- Memory logging at key phases for profiling

---

### 6. `AccessGraphService.java`

**Package:** `com.saviynt.sod.evaluation.service`  
**Purpose:** Builds the in-memory role hierarchy graph and resolves each user's effective entitlements via BFS.  
**How it fits:** Called during Phase 1. Provides the resolved entitlement arrays that Phase 2 evaluates against.

**Key Methods:**

| Method | Purpose |
|--------|---------|
| `loadGraph(securitySystemId)` | Loads ENTITLEMENTS2 as adjacency list + builds reverse graph |
| `resolveEntitlements(directAssignments, maxDepth)` | BFS from direct assignments, returns sorted long[] |
| `resolveFromNode(startNode, maxDepth)` | BFS from single node with memoization |
| `getImmediateChildren(parentNode)` | O(1) lookup of children |
| `getImmediateParents(childNode)` | O(1) lookup via reverse graph |
| `findAncestorIn(targetNode, directAssignments)` | Finds which direct assignment reaches a target |
| `findPath(directAssignments, targetEnt, maxDepth)` | BFS with parent tracking for path reconstruction |
| `getGraph()` / `getReverseGraph()` | Expose graph references for evidence collection |

**Data Structures:**
- `graph`: `Map<Long, long[]>` — parent → sorted children array (primitive for cache locality)
- `reverseGraph`: `Map<Long, long[]>` — child → parents array
- `resolvedCache`: `Map<Long, long[]>` — memoization of BFS results per starting node

**Design Decisions:**
- Uses `long[]` instead of `List<Long>` for memory efficiency and CPU cache locality
- Memoization via `resolvedCache`: if role X was already BFS-resolved, reuse the result for any account that has role X. This is the key optimization — many accounts share the same roles.
- Reverse graph built at load time for O(1) ancestor lookups during evidence collection
- `maxDepth` configurable (default 14) — matches old system's maximum hierarchy depth
- Graph always reloaded fresh on each evaluation (ensures correctness after data imports)
- BFS uses `ArrayDeque` (not recursive) to avoid stack overflow on deep hierarchies

---

### 7. `FunctionEvaluationService.java`

**Package:** `com.saviynt.sod.evaluation.service`  
**Purpose:** Evaluates each function against all users and produces BitSets. The core computation engine.  
**How it fits:** Called during Phase 2. Produces the BitSets that Phase 3 intersects.

**Key Methods:**

| Method | Purpose |
|--------|---------|
| `evaluateNonSAP(condition, users, excludedFuncEnts, excludedEntPairs, graph)` | Evaluates NonSAP boolean condition against all users |
| `evaluateSAPWithEvidence(funcDef, endpointKey, users, roleAuthMap, starTcodeKeys, evidenceMap)` | Evaluates SAP function + collects evidence |
| `satisfiesAnySAPGroup(user, groups, userAuthIndex, starTcodeKeys)` | OR across condition groups |
| `satisfiesAllConditionsInGroup(user, conditions, userAuthIndex, starTcodeKeys)` | Groups by TCode, OR across TCodes, AND within TCode's auth |
| `hasMatchingAuth(userAuthIndex, condition)` | O(1) lookup + value range check |
| `buildUserAuthIndex(resolvedEnts, roleAuthMap)` | Pre-builds per-user auth index for fast lookups |
| `collectSAPEvidence(...)` | Records which tcode/role satisfied the function |
| `isExcludedByEntType(user, excludedEntPairs, graph)` | Full-depth graph walk for entitlement type exclusion |
| `walkAndCheckExcluded(...)` | Recursive DFS checking excluded pairs at each depth |
| `setGraphRefs(graph, reverseGraph)` | Receives graph references from orchestrator |
| `setConsiderPrecedingZeros(value)` | Runtime config toggle |
| `setAllowedTcodesPerFunc(allowed)` | Old system's sequential tcode-evaluated behavior |
| `setTcdResolvedTcodes(tcdResolved)` | TCD-field resolved tcode mappings |

**SAP Evaluation Logic (per user):**
1. Build `userAuthIndex`: composite key `(objectKey * 100000 + fieldKey)` → list of AuthEntry
2. For each condition group (OR across groups):
   - Group conditions by TCode
   - For each TCode (OR across TCodes):
     - Check tcode ownership (binary search in resolved ents) — star tcodes bypass this
     - Check ALL auth conditions for this TCode (AND within TCode)
     - Each auth condition: lookup by composite key, then check value ranges via `ValueMatcher`
3. If any group fully satisfied → bit set

**NonSAP Evaluation Logic (per user):**
1. Evaluate compiled boolean condition tree against sorted resolved entitlements
2. If satisfied, check function exclusion (excluded entitlement keys)
3. If still satisfied, check entitlement type exclusion (full-depth graph walk)

**Design Decisions:**
- `userAuthIndex` with composite key turns O(resolvedEnts × conditions) into O(conditions) per user
- Star tcode handling: bypasses binary search for tcode ownership
- Evidence collection happens during evaluation (zero extra cost) — avoids separate Phase 4 computation
- Entitlement type exclusion uses DFS with cycle protection (visited set)
- Graph references set via setter (not constructor) because they're loaded per-evaluation

---

### 8. `ViolationDetectionService.java`

**Package:** `com.saviynt.sod.evaluation.service`  
**Purpose:** Detects violations by AND-ing function BitSets for each risk.  
**How it fits:** Called during Phase 3. Takes BitSets from Phase 2, produces violation BitSets.

**Key Methods:**

| Method | Purpose |
|--------|---------|
| `detectViolations(risks, functionBitSets, funcEndpointMap)` | Main entry — iterates all risks × endpoints |
| `resolveEndpoints(risk, funcEndpointMap)` | Determines which endpoints to evaluate for a risk |
| `intersectFunctionsForRisk(risk, endpointKey, functionBitSets)` | AND all function BitSets for one risk |

**Algorithm:**
1. For each risk, determine relevant endpoints (always includes 0, adds SAPGROUP endpoints)
2. For each endpoint, AND all function BitSets
3. If ALL functions in the risk have a BitSet at this endpoint AND the intersection is non-empty → violation
4. Early exit: if intersection becomes empty mid-way, skip remaining functions

**Design Decisions:**
- `matched != risk.functionCount()` check replicates old system's `gotactual == totalfuninrisk` behavior
- Endpoint 0 always included (NonSAP + SAP plain functions evaluate at endpoint 0)
- BitSet AND is O(users/64) — sub-millisecond for typical workloads
- Returns `Map<String, BitSet>` keyed by `"riskId###endpointKey"` for Phase 4 consumption

---

### 9. `ValidationService.java`

**Package:** `com.saviynt.sod.evaluation.service`  
**Purpose:** Validates evaluation results against the old system's `sodrisks` table.  
**How it fits:** Called after Phase 4. Pure read-only — no writes.

**Key Methods:**

| Method | Purpose |
|--------|---------|
| `validate(violationBitSets, users, risks, rulesetKeys)` | Compares our violations vs existing |

**Algorithm:**
1. Build set of our violations as `"userIdentifier#riskId#endpointKey"` strings
2. Query existing `sodrisks` table for same rulesets (status IN 1,2,3 = open violations)
3. Compute: matches (intersection), false positives (ours - existing), false negatives (existing - ours)
4. Log sample FP/FN for debugging

**Output:** `ValidationReport` record with counts + sample violations for debugging.

**Design Decision:** String-based set comparison for simplicity. Logs samples of mismatches for quick debugging.

---

### 10. `ValueMatcher.java`

**Package:** `com.saviynt.sod.evaluation.service`  
**Purpose:** SAP authorization value matching logic. Determines if a user's auth value satisfies a function's required value.  
**How it fits:** Called by `FunctionEvaluationService.hasMatchingAuth()` during Phase 2.

**Key Methods:**

| Method | Purpose |
|--------|---------|
| `matches(funcMin, funcMax, roleMin, roleMax, absoluteValue, considerPrecedingZeros)` | Main entry — dispatches to appropriate comparison |
| `rangesOverlap(funcMin, funcMax, roleMin, roleMax)` | String-based range overlap |
| `rangesOverlapNumeric(funcMin, funcMax, roleMin, roleMax)` | Numeric range overlap (for all-digit values) |
| `isRangeTooBroad(minValue, maxValue)` | Checks if range spans > 1000 values |
| `isWildcard(value)` | Checks for `*` or `$`-prefixed values |
| `normalize(value)` | Trims, converts `$`-prefix to `*` |
| `stripLeadingZeros(value)` | Removes leading zeros (when `considerPrecedingZeros=true`) |
| `stripQuotes(value)` | Removes surrounding single quotes for absolute values |

**Matching Cases:**
1. Both wildcard → match
2. Function is wildcard → match (any account value satisfies)
3. Account is wildcard → match (covers all values)
4. Neither wildcard → numeric range overlap check

**Design Decisions:**
- Exact port of `violationFound()` from ECMv4 Groovy code
- Always uses numeric comparison for all-digit values (matches old system's `functionForOnlyDigits`)
- `considerPrecedingZeros` only controls leading zero stripping, not comparison method
- Absolute value matching: strips quotes from function side only, then does case-insensitive equality
- `$`-prefixed values (like `$BUKRS`) treated as wildcards
- Static utility class (no state, all methods static, private constructor)

---

### 11. `SodConfigDao.java`

**Package:** `com.saviynt.sod.evaluation.dao`  
**Purpose:** Data access for SOD configuration tables: rulesets, risks, functions, function_objects, function_entitlements, configuration.  
**How it fits:** Called during Phase 0 to load all configuration data.

**Key Methods:**

| Method | Purpose |
|--------|---------|
| `loadDefaultRulesetKeys()` | Loads rulesets where DEFAULTRULESET=1 |
| `loadActiveRisks(rulesetKeys)` | Loads risks with STATUS=0 and FUNCTION1KEY IS NOT NULL |
| `loadFunctions(functionKeys)` | Loads function metadata (name, type, exclusionQry) |
| `loadNonSAPConditions(functionKeys)` | Loads FUNCTION_ENTITLEMENTS → compiles to NonSAPCondition trees |
| `loadSAPFunctionDefs(functionKeys)` | Loads FUNCTION_OBJECTS → builds SAPFunctionDef with grouped conditions |
| `loadStarTcodeKeys(securitySystemId)` | Finds entitlement_values where value='*' and type='tcode' |
| `loadConsiderPrecedingZeros()` | Reads CONFIGURATION table for sod.fieldval.considerPrecedingZeros |
| `loadExcludedEntPairs()` | Loads excluded entitlement pairs for NonSAP type exclusion |
| `loadTcdFieldResolvedTcodes(functionKeys, securitySystemId)` | Resolves TCD-field (fieldkey=65) references to actual tcode keys |
| `loadTcodesWithDirectRoleParent(securitySystemId)` | Finds tcodes reachable from direct-assignment roles (for detail row filtering) |
| `loadFunctionExcludedEnts(exclusionQry)` | Executes a function's exclusion query to get excluded ent keys |
| `loadExistingViolationKeys(rulesetKeys)` | Loads existing open violations for closure detection |

**Design Decisions:**
- `FUNCTION1KEY IS NOT NULL` filter: old system's `getFinalMap` starts with function1; if NULL, all subsequent functions are skipped
- SAP function loading groups by: function → endpoint → groupKey → "tcode#obj#field" → value ranges. This mirrors the old system's evaluation structure.
- NonSAP condition compilation: converts DB rows into a sealed interface tree (And/Or/HasEntitlement) — replaces GroovyShell.evaluate()
- Excluded ent pairs: joins entitlements2 → entitlement_values → entitlement_types to find "Excluded%" type nodes
- TCD-field resolution: for function_objects with fieldkey=65, resolves MINVALUE string to actual tcode entitlement_valuekey

---

### 12. `AccessDataDao.java`

**Package:** `com.saviynt.sod.evaluation.dao`  
**Purpose:** Data access for core access tables: ENTITLEMENTS2, ACCOUNT_ENTITLEMENTS1, ENTITLEMENT_OBJECTS.  
**How it fits:** Called during Phase 1 to load graph edges, direct assignments, and auth data.

**Key Methods:**

| Method | Purpose |
|--------|---------|
| `loadEntitlements2(securitySystemId)` | Loads role hierarchy graph (parent→child edges) |
| `loadAccountEntitlements(accountFilter, entQuery)` | Loads direct account→role assignments |
| `loadAccountMetadata(accountFilter)` | Loads account→user mapping + endpoint info |
| `loadEntitlementObjects(securitySystemId)` | Loads SAP auth data (role→object→field→value) |
| `getDataSource()` | Exposes DataSource for direct JDBC usage in orchestrator |

**Design Decisions:**
- All queries scoped to security system via JOIN chain: entitlement_values → entitlement_types → endpoints → securitysystem
- Suspended accounts excluded: `STATUS <> 'SUSPENDED FROM IMPORT SERVICE'`
- Uses raw JDBC (JdbcTemplate) — no ORM overhead
- Returns primitive-friendly structures: `Map<Long, List<Long>>` for graph, `Map<Long, long[]>` for metadata
- `objectdeleted = 0 OR objectdeleted IS NULL` filter on entitlement_objects (matches old system)
- Initial HashMap capacity hints (30K, 50K) to reduce rehashing

---

### 13. `Risk.java`

**Package:** `com.saviynt.sod.evaluation.model`  
**Purpose:** Immutable representation of a SOD Risk — a forbidden combination of 2-5 functions.  
**Fields:** `riskId`, `riskName`, `rulesetKey`, `functionKeys` (List<Long>)  
**Key Method:** `functionCount()` — returns number of functions in this risk.

---

### 14. `SodFunction.java`

**Package:** `com.saviynt.sod.evaluation.model`  
**Purpose:** Represents a SOD Function — a business capability defined as a predicate over entitlements.  
**Fields:** `functionKey`, `functionName`, `type` (enum), `exclusionQuery`  
**Inner Enum:** `FunctionType` — SAP, NONSAP, SAPGROUP. Includes `from(String)` parser that handles null (defaults to NONSAP).

---

### 15. `SAPFunctionDef.java`

**Package:** `com.saviynt.sod.evaluation.model`  
**Purpose:** Pre-loaded SAP function definition with grouped auth conditions.  
**Structure:**
- `functionKey`, `endpoints` (List<Long>), `conditionsByEndpoint` (Map<Long, List<AuthConditionGroup>>)
- `AuthConditionGroup`: groupKey + list of AuthCondition (AND within group, OR across groups)
- `AuthCondition`: tcodeKey + objectKey + fieldKey + list of ValueRange (OR across ranges)
- `ValueRange`: minValue + maxValue + absoluteValue flag

**Design Decision:** Deeply nested record structure mirrors the evaluation semantics: OR(groups) → OR(tcodes) → AND(auth conditions) → OR(value ranges).

---

### 16. `NonSAPCondition.java`

**Package:** `com.saviynt.sod.evaluation.model`  
**Purpose:** Pre-compiled boolean condition tree for NonSAP functions. Replaces GroovyShell.evaluate().  
**Structure:** Sealed interface with three implementations:
- `HasEntitlement(long entitlementKey)` — leaf node, binary search in sorted array
- `And(left, right)` — short-circuit AND
- `Or(left, right)` — short-circuit OR

**Key Method:** `compile(List<FunctionEntitlementRow> rows)` — builds the tree from DB rows.  
**Compilation Logic:** Left-to-right, `&&` binds tighter than `||` (matches Groovy behavior). Operators read from `nextOperator` of previous row or `prevOperator` of current row.

**Design Decision:** Sealed interface + records = type-safe, immutable, fast. Binary search on sorted `long[]` for O(log n) entitlement membership check.

---

### 17. `AuthEntry.java`

**Package:** `com.saviynt.sod.evaluation.model`  
**Purpose:** A single auth object/field/value entry on a SAP role.  
**Fields:** `roleKey`, `objectKey`, `fieldKey`, `minValue`, `maxValue`  
**Source Table:** ENTITLEMENT_OBJECTS

---

### 18. `UserAccess.java`

**Package:** `com.saviynt.sod.evaluation.model`  
**Purpose:** Resolved access picture for a single user, built during Phase 1.  
**Fields:**
- `userKey` — the user identifier (or -1*accountKey for unmapped accounts)
- `userIndex` — sequential index for BitSet position
- `resolvedEntitlements` — sorted long[] of all reachable entitlement keys via BFS
- `accounts` — list of AccountAccess (per-account breakdown for evidence)
- `entToDirectAssignment` — mapping for Phase 4 (currently unused, nullable)

**Inner Record:** `AccountAccess(accountKey, endpointKey, directAssignments)` — per-account data.

**Design Decision:** Sorted `resolvedEntitlements` enables O(log n) binary search during evaluation. Multiple accounts per user are merged (same user can have accounts on different endpoints).

---

### 19. `FunctionEvidence.java`

**Package:** `com.saviynt.sod.evaluation.model`  
**Purpose:** Evidence of WHY a user satisfies a function. Collected during Phase 2, consumed during Phase 4.  
**Fields:** `accountKey`, `tcodeKey`, `assocSapRole` (immediate parent of tcode), `directRole` (direct assignment that reaches it), `endpointKey`

**Design Decision:** Lightweight record — one per tcode×role match. Stored in ConcurrentHashMap keyed by `"userIndex###funcKey"`.

---

### 20. `Violation.java`

**Package:** `com.saviynt.sod.evaluation.model`  
**Purpose:** A detected SOD violation with full evidence chain.  
**Fields:** `userKey`, `userIdentifier`, `riskId`, `endpointKey`, `evidence` (list)  
**Inner Record:** `ViolationEvidence` — per-function evidence including account, entitlement, role, tcode, object, field, value.

**Note:** This model is defined but the current implementation writes directly from BitSets + evidence map without constructing full Violation objects (optimization — avoids object allocation for 100K+ violations).

---

## Test Infrastructure

### `TestDataGenerator.java`

**Package:** `com.saviynt.sod.testdata`  
**Purpose:** Generates large-scale test data covering all 24 evaluation scenarios.  
**Profiles:**
- `small`: 600 accounts/scenario, 50 bulk roles, 5 tcodes/role — for correctness verification
- `hitachi`: 3000 accounts/scenario, 5000 bulk roles, 100 tcodes/role — for stress testing

**Constants:** System 200, Endpoint 200, Ruleset 200. ID sequences start at high values (3M+, 30M+, 600K+) to avoid conflicts with real data.

### `ScenarioBuilder.java`

**Package:** `com.saviynt.sod.testdata`  
**Purpose:** Builds individual test scenarios with helper methods for creating tcodes, roles, auth, functions, risks, and batch accounts.  
**Key Helpers:** `createTcode()`, `createRole()`, `addHierarchy()`, `addAuth()`, `createAccount()`, `assignRole()`, `createSAPFunction()`, `createNonSAPFunction()`, `addFuncObj()`, `addFuncEnt()`, `createRisk()`, `batchAccounts()`

---

## Class Hierarchy

```
Records (immutable data):
  Risk, SodFunction, SAPFunctionDef, AuthEntry, UserAccess, FunctionEvidence, Violation
  EvaluationRequest, EvaluationResult
  ValidationService.ValidationReport

Sealed Interface:
  NonSAPCondition
    ├── HasEntitlement
    ├── And
    └── Or

Enums:
  SodFunction.FunctionType (SAP, NONSAP, SAPGROUP)

Spring Services (@Service):
  EvaluationOrchestrator, AccessGraphService, FunctionEvaluationService,
  ViolationDetectionService, ValidationService

Spring Repositories (@Repository):
  SodConfigDao, AccessDataDao

Spring Controllers (@RestController):
  EvaluationController

Utility (static):
  ValueMatcher
```
