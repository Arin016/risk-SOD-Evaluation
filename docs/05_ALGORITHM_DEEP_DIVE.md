# SOD Evaluation Microservice — Algorithm Deep Dive

## Overview

This document provides phase-by-phase pseudocode for the SOD evaluation pipeline. For each phase: the exact algorithm, data structures used, time/space complexity, and how it differs from the old ECMv4 system.

The pipeline has 5 phases executed sequentially:

```
Phase 0: Load Config         → O(functions + risks)
Phase 1: Load Graph + BFS    → O(edges + accounts × avg_reachable)
Phase 2: Evaluate Functions   → O(users × functions × avg_conditions) [parallel]
Phase 3: Detect Violations   → O(risks × users/64)
Phase 4: Persist             → O(violations × evidence_per_violation)
```

---

## Phase 0: Load Configuration

### Algorithm

```pseudocode
FUNCTION loadConfig(rulesetKeys, securitySystemId):
    // Step 1: Load risks
    risks = SELECT * FROM risks WHERE STATUS=0 AND FUNCTION1KEY IS NOT NULL AND RULESETKEY IN (rulesetKeys)
    allFunctionKeys = UNION(risk.function1key..function5key for each risk)

    // Step 2: Load functions and separate by type
    functions = SELECT * FROM functions WHERE FUNCTIONKEY IN (allFunctionKeys)
    sapFuncKeys = filter(functions, type IN (SAP, SAPGROUP))
    nonSAPFuncKeys = filter(functions, type = NONSAP)

    // Step 3: Load SAP function definitions (grouped conditions)
    FOR EACH sapFuncKey:
        rows = SELECT * FROM function_objects WHERE FUNCTIONKEY=sapFuncKey AND STATUS=0
        GROUP rows BY: endpoint → groupKey → "tcode#obj#field" → valueRanges
        Build SAPFunctionDef(funcKey, endpoints, conditionsByEndpoint)

    // Step 4: Load NonSAP conditions (boolean trees)
    FOR EACH nonSAPFuncKey:
        rows = SELECT * FROM function_entitlements WHERE FUNCTIONKEY=key ORDER BY CONDITIONPOSITION
        condition = compile(rows)  // builds And/Or/HasEntitlement tree

    // Step 5: Load star tcode keys
    starTcodeKeys = SELECT ENTITLEMENT_VALUEKEY FROM entitlement_values
                    WHERE ENTITLEMENT_VALUE='*' AND type='tcode' AND system=securitySystemId

    // Step 6: Load config flags
    considerPrecedingZeros = SELECT CONFIGDATA FROM configuration WHERE CONFIGKEY='sod.fieldval.considerPrecedingZeros'

    // Step 7: Load excluded entitlement pairs (NonSAP type exclusion)
    excludedEntPairs = SELECT parentKey#PROGRAM FROM entitlements2 JOIN entitlement_values
                       WHERE type LIKE 'Excluded%' AND PROGRAM IS NOT NULL

    // Step 8: Load TCD-field resolved tcodes
    tcdResolvedTcodes = FOR function_objects WHERE fieldkey=65:
                        resolve MINVALUE string → actual tcode entitlement_valuekey

    // Step 9: Pre-compute allowedTcodesPerFunc (old system's sequential state)
    globalTcodeEvaluated = {}
    FOR EACH sapFunc IN sorted(sapFuncKeys) BY funcKey ASC:
        funcTcodes = all tcodes referenced by this function
        allowedTcodesPerFunc[funcKey] = funcTcodes - globalTcodeEvaluated
        globalTcodeEvaluated += funcTcodes

    RETURN all loaded data
```

### NonSAP Condition Compilation

```pseudocode
FUNCTION compile(rows):
    IF rows.size == 0: RETURN AlwaysFalse
    IF rows.size == 1: RETURN HasEntitlement(rows[0].entitlementKey)

    result = HasEntitlement(rows[0].entitlementKey)
    FOR i = 1 TO rows.size-1:
        term = HasEntitlement(rows[i].entitlementKey)
        operator = rows[i-1].nextOperator OR rows[i].prevOperator
        IF operator contains "||" OR "OR":
            result = Or(result, term)
        ELSE:
            result = And(result, term)
    RETURN result
```

### Data Structures

| Structure | Type | Size |
|-----------|------|------|
| risks | List<Risk> | ~200-300 entries |
| functions | Map<Long, SodFunction> | ~100-150 entries |
| sapFunctionDefs | Map<Long, SAPFunctionDef> | ~100 entries |
| nonSAPConditions | Map<Long, NonSAPCondition> | ~10-50 entries |
| starTcodeKeys | Set<Long> | 0-5 entries |
| excludedEntPairs | Set<String> | 0-100 entries |
| allowedTcodesPerFunc | Map<Long, Set<Long>> | ~100 entries |

### Complexity

- **Time:** O(F × C) where F = functions, C = avg conditions per function
- **Space:** O(F × C) for all loaded structures
- **DB Queries:** ~10 queries total

### Difference from Old System (ECMv4)

| Aspect | Old System | New System |
|--------|-----------|------------|
| Condition compilation | GroovyShell.evaluate() at runtime | Pre-compiled sealed interface tree |
| Function loading | Loaded per-function during evaluation | All loaded upfront in Phase 0 |
| Star tcode detection | Checked during getTcodeRoleMap | Pre-loaded set, checked during evaluation |
| TCD-field resolution | Inline during evaluation | Pre-resolved in Phase 0 |
| allowedTcodesPerFunc | Implicit via mutable `tcodeEvaluated` set | Explicit pre-computation |

---

## Phase 1: Load Graph + Resolve Access (BFS)

### Algorithm

```pseudocode
FUNCTION loadGraphAndResolve(securitySystemId, accountFilter, maxDepth):
    // Step 1: Load hierarchy graph
    edges = SELECT parent, child FROM entitlements2
            JOIN entitlement_values → entitlement_types → endpoints
            WHERE securitySystemKey = securitySystemId

    graph = {}  // parent → long[] children
    reverseGraph = {}  // child → long[] parents
    FOR EACH (parent, child) IN edges:
        graph[parent].add(child)
        reverseGraph[child].add(parent)
    Convert all List<Long> to sorted long[] (cache locality)

    // Step 2: Load direct assignments
    directAssignments = SELECT accountKey, entitlementKey
                        FROM account_entitlements1 ae
                        JOIN accounts a ON ae.ACCOUNTKEY = a.ACCOUNTKEY
                        WHERE a.STATUS <> 'SUSPENDED FROM IMPORT SERVICE'
                        AND a.SYSTEMID = securitySystemId
    // Result: Map<accountKey, List<entitlementKey>>

    // Step 3: Load account metadata (user mapping)
    accountMetadata = SELECT accountKey, userKey, endpointKey
                      FROM accounts LEFT JOIN user_accounts
    // Result: Map<accountKey, [userKey, endpointKey, systemId]>

    // Step 4: Load auth entries
    roleAuthMap = SELECT roleKey, objectKey, fieldKey, minValue, maxValue
                  FROM entitlement_objects
                  WHERE objectdeleted=0 AND system=securitySystemId
    // Result: Map<roleKey, List<AuthEntry>>

    // Step 5: Group accounts by user
    userAccounts = {}
    FOR EACH accountKey IN directAssignments.keys():
        userKey = accountMetadata[accountKey].userKey
        IF userKey == 0: userKey = -1 * accountKey  // unmapped account
        userAccounts[userKey].add(accountKey)

    // Step 6: BFS resolve per user
    users = []
    FOR EACH (userKey, accountKeys) IN userAccounts:
        mergedEnts = {}
        accountAccesses = []
        FOR EACH accountKey IN accountKeys:
            directArr = directAssignments[accountKey]
            resolved = resolveEntitlements(directArr, maxDepth)
            mergedEnts.addAll(resolved)
            accountAccesses.add(AccountAccess(accountKey, endpoint, directArr))
        sortedEnts = sort(mergedEnts)
        users.add(UserAccess(userKey, index++, sortedEnts, accountAccesses))

    RETURN users, roleAuthMap
```

### BFS Resolution with Memoization

```pseudocode
resolvedCache = {}  // global cache: startNode → sorted long[]

FUNCTION resolveEntitlements(directAssignments[], maxDepth):
    merged = {}
    FOR EACH root IN directAssignments:
        resolved = resolveFromNode(root, maxDepth)
        merged.addAll(resolved)
    RETURN sort(merged)

FUNCTION resolveFromNode(startNode, maxDepth):
    IF resolvedCache.contains(startNode):
        RETURN resolvedCache[startNode]

    visited = {}
    queue = [(startNode, depth=0)]

    WHILE queue NOT empty:
        (node, depth) = queue.poll()
        IF node IN visited: CONTINUE
        visited.add(node)
        IF depth >= maxDepth: CONTINUE
        FOR EACH child IN graph[node]:
            IF child NOT IN visited:
                queue.add((child, depth+1))

    result = sort(visited)
    resolvedCache[startNode] = result
    RETURN result
```

### Data Structures

| Structure | Type | Size (Hitachi) |
|-----------|------|----------------|
| graph | Map<Long, long[]> | ~2,600 parent nodes, 500K edges |
| reverseGraph | Map<Long, long[]> | ~500K child nodes |
| resolvedCache | Map<Long, long[]> | grows to ~5K-50K entries |
| directAssignments | Map<Long, List<Long>> | ~91K accounts |
| roleAuthMap | Map<Long, List<AuthEntry>> | ~3,500 roles with auth |
| users | List<UserAccess> | ~91K users |

### Complexity

- **Graph Load:** O(E) where E = edges in entitlements2
- **BFS per account:** O(reachable nodes) — but memoized, so amortized O(1) for shared roles
- **Total BFS:** O(A × R_avg) where A = accounts, R_avg = avg unique direct assignments not yet cached
- **Space:** O(E + A × R_resolved) where R_resolved = avg resolved ents per user

### Difference from Old System (ECMv4)

| Aspect | Old System | New System |
|--------|-----------|------------|
| Graph traversal | Depth-N self-join SQL (up to 14 JOINs) | In-memory BFS with memoization |
| Per-account cost | 1 DB query per depth level per account | O(reachable) in-memory, cached |
| Caching | None — re-queries same roles | resolvedCache: same role resolved once |
| Memory | Stores full HashMap per account | Sorted long[] per user (compact) |
| Reverse graph | Not built | Built at load time for O(1) ancestor lookup |
| Max depth | 14 (hardcoded in SQL) | Configurable (default 14) |

---

## Phase 2: Evaluate Functions → BitSets (Parallel)

### Overview

Phase 2 is the computational core. It evaluates every function against every user and produces a BitSet per function where bit `i` is set if user `i` satisfies that function. All functions are evaluated in parallel using Java 21 virtual threads.

### SAP Function Evaluation Algorithm

```pseudocode
FUNCTION evaluateSAPWithEvidence(funcDef, endpointKey, users, roleAuthMap, starTcodeKeys, evidenceMap):
    bits = new BitSet(users.size)
    groups = funcDef.conditionsByEndpoint[endpointKey]
    IF groups IS NULL: RETURN bits

    FOR i = 0 TO users.size-1:
        user = users[i]

        // Step 1: Build per-user auth index (O(resolvedEnts))
        userAuthIndex = buildUserAuthIndex(user.resolvedEntitlements, roleAuthMap)
        // Key: objectKey * 100000 + fieldKey → List<AuthEntry>

        // Step 2: Check if user satisfies ANY group (OR across groups)
        IF satisfiesAnySAPGroup(user, groups, userAuthIndex, starTcodeKeys):
            bits.set(i)
            IF evidenceMap != NULL:
                collectSAPEvidence(user, i, funcKey, endpointKey, groups, starTcodeKeys, evidenceMap, userAuthIndex)

    RETURN bits
```

### User Auth Index Construction

```pseudocode
FUNCTION buildUserAuthIndex(resolvedEnts[], roleAuthMap):
    index = {}  // compositeKey → List<AuthEntry>
    FOR EACH entKey IN resolvedEnts:
        auths = roleAuthMap[entKey]
        IF auths != NULL:
            FOR EACH auth IN auths:
                compositeKey = auth.objectKey * 100000 + auth.fieldKey
                index[compositeKey].add(auth)
    RETURN index
```

**Why composite key?** Avoids nested Map<objectKey, Map<fieldKey, List<AuthEntry>>>. Single HashMap lookup instead of two. The multiplier 100000 ensures no collisions (fieldKey is always < 100000).

### SAP Group Satisfaction (OR across groups, OR across tcodes, AND within tcode)

```pseudocode
FUNCTION satisfiesAnySAPGroup(user, groups, userAuthIndex, starTcodeKeys):
    FOR EACH group IN groups:                          // OR across groups
        IF satisfiesAllConditionsInGroup(user, group.conditions, userAuthIndex, starTcodeKeys):
            RETURN TRUE
    RETURN FALSE

FUNCTION satisfiesAllConditionsInGroup(user, conditions, userAuthIndex, starTcodeKeys):
    // Group conditions by TCode
    byTCode = groupBy(conditions, condition.tcodeKey)  // LinkedHashMap preserves order

    FOR EACH (tcodeKey, tcodeConditions) IN byTCode:   // OR across TCodes
        // Step 1: Does user have this TCode?
        IF tcodeKey NOT IN starTcodeKeys:
            IF binarySearch(user.resolvedEntitlements, tcodeKey) < 0:
                CONTINUE  // doesn't have this TCode, try next

        // Step 2: Do ALL auth conditions for this TCode match? (AND within TCode)
        allAuthMatched = TRUE
        FOR EACH condition IN tcodeConditions:
            IF NOT hasMatchingAuth(userAuthIndex, condition):
                allAuthMatched = FALSE
                BREAK

        IF allAuthMatched:
            RETURN TRUE  // This TCode + all its auth matched → group satisfied

    RETURN FALSE  // No TCode fully satisfied
```

### Auth Value Matching (ValueMatcher)

```pseudocode
FUNCTION hasMatchingAuth(userAuthIndex, condition):
    compositeKey = condition.objectKey * 100000 + condition.fieldKey
    auths = userAuthIndex[compositeKey]
    IF auths IS NULL: RETURN FALSE

    FOR EACH auth IN auths:
        FOR EACH range IN condition.valueRanges:       // OR across value ranges
            IF ValueMatcher.matches(range.min, range.max, auth.min, auth.max, range.absoluteValue, considerPrecedingZeros):
                RETURN TRUE
    RETURN FALSE

FUNCTION ValueMatcher.matches(funcMin, funcMax, roleMin, roleMax, absoluteValue, considerPrecedingZeros):
    normalize(funcMin, funcMax, roleMin, roleMax)
    IF empty max: max = min (single value)
    IF considerPrecedingZeros: strip leading zeros from all values

    IF absoluteValue:
        stripQuotes(funcMin, funcMax)
        RETURN funcMin.equalsIgnoreCase(roleMin) OR funcMin.equalsIgnoreCase(roleMax)
               OR funcMax.equalsIgnoreCase(roleMin) OR funcMax.equalsIgnoreCase(roleMax)

    IF isWildcard(funcMin) OR isWildcard(roleMin): RETURN TRUE

    // Numeric range overlap (for all-digit values)
    IF allDigits(funcMin, funcMax, roleMin, roleMax):
        fMin, fMax, rMin, rMax = parseLong(...)
        RETURN (fMin <= rMin <= fMax) OR (fMin <= rMax <= fMax)
               OR (rMin <= fMin <= rMax) OR (rMin <= fMax <= rMax)
    ELSE:
        // String comparison range overlap
        RETURN (funcMin <= roleMin <= funcMax) OR (funcMin <= roleMax <= funcMax)
               OR (roleMin <= funcMin <= roleMax) OR (roleMin <= funcMax <= roleMax)
```

### NonSAP Function Evaluation Algorithm

```pseudocode
FUNCTION evaluateNonSAP(condition, users, excludedFuncEnts, excludedEntPairs, graph):
    bits = new BitSet(users.size)
    FOR i = 0 TO users.size-1:
        user = users[i]

        // Step 1: Evaluate boolean condition tree
        IF NOT condition.evaluate(user.resolvedEntitlements):
            CONTINUE

        // Step 2: Function exclusion query check
        IF excludedFuncEnts NOT empty:
            IF any(user.resolvedEntitlements) IN excludedFuncEnts:
                CONTINUE

        // Step 3: Entitlement type exclusion (full-depth graph walk)
        IF excludedEntPairs NOT empty:
            IF isExcludedByEntType(user, excludedEntPairs, graph):
                CONTINUE

        bits.set(i)
    RETURN bits

FUNCTION condition.evaluate(sortedEnts):  // recursive tree evaluation
    MATCH condition:
        HasEntitlement(key): RETURN binarySearch(sortedEnts, key) >= 0
        And(left, right):    RETURN left.evaluate(sortedEnts) AND right.evaluate(sortedEnts)
        Or(left, right):     RETURN left.evaluate(sortedEnts) OR right.evaluate(sortedEnts)
```

### Entitlement Type Exclusion (Full-Depth Graph Walk)

```pseudocode
FUNCTION isExcludedByEntType(user, excludedEntPairs, graph):
    FOR EACH account IN user.accounts:
        FOR EACH directAssignment IN account.directAssignments:
            // Depth 1: check direct assignment against itself
            IF (directAssignment + "#" + directAssignment) IN excludedEntPairs:
                RETURN TRUE
            // Depth 2+: walk graph checking every intermediate node
            IF walkAndCheckExcluded(directAssignment, directAssignment, excludedEntPairs, graph, depth=1, maxDepth=14, visited={}):
                RETURN TRUE
    RETURN FALSE

FUNCTION walkAndCheckExcluded(parentEnt, currentNode, excludedEntPairs, graph, depth, maxDepth, visited):
    IF depth > maxDepth: RETURN FALSE
    children = graph.getImmediateChildren(currentNode)
    IF children IS NULL: RETURN FALSE
    FOR EACH child IN children:
        IF child IN visited: CONTINUE  // cycle protection
        visited.add(child)
        IF (parentEnt + "#" + child) IN excludedEntPairs: RETURN TRUE
        IF walkAndCheckExcluded(parentEnt, child, excludedEntPairs, graph, depth+1, maxDepth, visited):
            RETURN TRUE
    RETURN FALSE
```

### SAP Evidence Collection

```pseudocode
FUNCTION collectSAPEvidence(user, userIndex, funcKey, endpointKey, groups, starTcodeKeys, evidenceMap, userAuthIndex):
    accountKey = user.accounts[0].accountKey
    directs = user.accounts[0].directAssignments
    evidences = []

    FOR EACH group IN groups:
        byTCode = groupBy(group.conditions, tcodeKey)
        FOR EACH (tcodeKey, tcodeConditions) IN byTCode:
            IF tcodeKey IN starTcodeKeys:
                // Star tcode: write one row with first direct assignment
                evidences.add(FunctionEvidence(accountKey, tcodeKey, directs[0], directs[0], endpointKey))
                CONTINUE

            // Does user have this tcode?
            IF binarySearch(user.resolvedEntitlements, tcodeKey) < 0: CONTINUE

            // Check ALL required objects have auth entries (object+field presence)
            requiredObjFields = {cond.objectKey * 100000 + cond.fieldKey FOR cond IN tcodeConditions}
            IF any(requiredObjFields) NOT IN userAuthIndex: CONTINUE

            // Find roles that are parents of this tcode AND user has them
            tcodeParents = reverseGraph[tcodeKey]
            IF tcodeParents IS NULL: CONTINUE

            FOR EACH roleKey IN tcodeParents:
                IF binarySearch(user.resolvedEntitlements, roleKey) < 0: CONTINUE
                // Find which direct assignment reaches this role
                FOR EACH d IN directs:
                    IF d == roleKey:
                        evidences.add(FunctionEvidence(accountKey, tcodeKey, roleKey, d, endpointKey))
                    ELSE IF roleKey IN graph[d].children:
                        evidences.add(FunctionEvidence(accountKey, tcodeKey, roleKey, d, endpointKey))
                        BREAK

        IF evidences NOT empty: BREAK  // old system breaks after first group with matches

    IF evidences NOT empty:
        evidenceMap["userIndex###funcKey"] = evidences
```

### Parallelism Model

```pseudocode
executor = newVirtualThreadPerTaskExecutor()
futures = []

FOR EACH nonSAPFunc:
    futures.add(executor.submit(() → evaluateNonSAP(...)))

FOR EACH sapFunc:
    FOR EACH endpoint IN sapFunc.endpoints:
        futures.add(executor.submit(() → evaluateSAPWithEvidence(...)))

// Wait for all to complete
FOR EACH future IN futures:
    future.get()
```

### Data Structures

| Structure | Type | Size (Hitachi) |
|-----------|------|----------------|
| functionBitSets | ConcurrentHashMap<String, BitSet> | ~150 entries, each 91K bits (~11 KB) |
| evidenceMap | ConcurrentHashMap<String, List<FunctionEvidence>> | ~50K entries |
| userAuthIndex (per user, temporary) | HashMap<Long, List<AuthEntry>> | ~50-500 entries |

### Complexity

- **Per user per SAP function:** O(resolvedEnts) to build auth index + O(groups × tcodes × conditions) to evaluate
- **Per user per NonSAP function:** O(condition_tree_depth × log(resolvedEnts))
- **Total:** O(users × functions × avg_cost_per_function) — but parallelized across virtual threads
- **Space:** O(users/8) per BitSet + O(violators × evidence) for evidence map

### Difference from Old System (ECMv4)

| Aspect | Old System | New System |
|--------|-----------|------------|
| Execution | Sequential (one function at a time) | Parallel (all functions simultaneously) |
| Per-user data | HashMap<userKey, Set<entitlementData>> | BitSet (1 bit per user) |
| Auth lookup | Linear scan through all role auth | O(1) via composite-key HashMap |
| Evidence | Written during evaluation (interleaved I/O) | Collected in memory, written in Phase 4 |
| Memory | Giant HashMap per function | One BitSet per function (~11 KB for 91K users) |
| Mutable state | Shared `tcodeEvaluated` set across functions | Pre-computed `allowedTcodesPerFunc` (immutable) |

---

## Phase 3: Detect Violations (BitSet AND)

### Algorithm

```pseudocode
FUNCTION detectViolations(risks, functionBitSets, funcEndpointMap):
    violations = {}

    FOR EACH risk IN risks:
        // Determine endpoints to evaluate
        endpoints = {0}  // always include endpoint 0
        FOR EACH funcKey IN risk.functionKeys:
            endpoints.addAll(funcEndpointMap[funcKey])

        FOR EACH endpointKey IN endpoints:
            result = intersectFunctionsForRisk(risk, endpointKey, functionBitSets)
            IF result != NULL AND result NOT empty:
                violations["riskId###endpointKey"] = result

    RETURN violations

FUNCTION intersectFunctionsForRisk(risk, endpointKey, functionBitSets):
    result = NULL
    matched = 0

    FOR EACH funcKey IN risk.functionKeys:
        funcBits = functionBitSets["funcKey###endpointKey"]
        IF funcBits IS NULL: CONTINUE  // function not evaluated at this endpoint

        matched++
        IF result IS NULL:
            result = clone(funcBits)
        ELSE:
            result.AND(funcBits)          // BitSet AND — O(users/64)
            IF result IS empty: RETURN NULL  // early exit optimization

    // Only return if ALL functions matched (replicates old system's gotactual == totalfuninrisk)
    IF matched != risk.functionCount(): RETURN NULL
    RETURN result
```

### Data Structures

| Structure | Type | Size |
|-----------|------|------|
| violations | Map<String, BitSet> | ~200-500 entries (risk×endpoint combos with violations) |
| Each BitSet | BitSet | O(users/64) bytes = ~11 KB for 91K users |

### Complexity

- **Time:** O(risks × functions_per_risk × users/64) — sub-millisecond for typical workloads
- **Space:** O(risks × users/64) for violation BitSets
- **The AND operation:** Java's `BitSet.and()` operates on `long[]` internally — 64 users compared per CPU instruction

### Why This Is Fast

For 272 risks × 91,500 users:
- Each BitSet is ~11 KB (91500/8 bytes)
- AND operation: ~1,430 long comparisons per pair
- Total: 272 × 2 × 1,430 = ~778K long comparisons = **< 1 millisecond**

### Difference from Old System (ECMv4)

| Aspect | Old System | New System |
|--------|-----------|------------|
| Detection method | HashMap intersection per risk per user | BitSet AND (bulk operation) |
| Per-risk cost | O(users × set_intersection_cost) | O(users/64) |
| Early exit | None | If AND result is empty, skip remaining functions |
| Endpoint handling | Complex per-function endpoint logic | Simple: evaluate at all relevant endpoints |

---

## Phase 4: Persist (Evidence + LOAD DATA INFILE)

### Algorithm

```pseudocode
FUNCTION persistViolations(violationBitSets, users, risks, jobId, ...):
    // Step 1: Clear previous run
    DELETE FROM sodrisk_entitlement_new_job WHERE SODKEY IN (SELECT ... WHERE RULESETKEY IN (...))
    DELETE FROM sodrisks_new_job WHERE RISKKEY IN (SELECT ... WHERE RULESETKEY IN (...))

    // Step 2: Write summary rows (batch INSERT)
    FOR EACH risk IN risks:
        FOR EACH (riskId###endpointKey, bits) IN violationBitSets matching this risk:
            FOR EACH setBit i IN bits:
                user = users[i]
                batch.add(INSERT INTO sodrisks_new_job (RISKKEY, RISKCODE, USERIDENTIFIER, ENDPOINTKEY, STATUS, JOBID)
                          VALUES (riskId, riskName, user.userKey, endpointKey, 1, jobId))
            FLUSH batch every 1000 rows

    // Step 3: Fetch generated SODKEYs
    sodKeyMap = SELECT SODKEY, USERIDENTIFIER, RISKKEY, ENDPOINTKEY FROM sodrisks_new_job WHERE JOBID=jobId
    // Key: "userKey###riskId###endpointKey" → SODKEY

    // Step 4: Write detail rows to temp CSV file
    tempFile = createTempFile("sod_detail_", ".csv")
    writer = BufferedWriter(tempFile, bufferSize=1MB)

    FOR EACH risk IN risks:
        FOR EACH (riskId###endpointKey, bits) IN violationBitSets:
            FOR EACH setBit i IN bits:
                user = users[i]
                sodKey = sodKeyMap["userKey###riskId###endpointKey"]

                FOR EACH funcKey IN risk.functionKeys:
                    evidences = evidenceMap["i###funcKey"]
                    IF evidences != NULL:
                        // SAP: write one row per evidence entry
                        FOR EACH ev IN evidences:
                            IF ev.endpointKey == endpointKey OR ev.endpointKey == 0:
                                writer.write(TSV: sodKey, accountKey, assocSapRole, funcKey, tcodeKey, 2, directRole)
                    ELSE:
                        // NonSAP fallback: find matching function entitlements
                        funcEntKeys = getFunctionEntitlementKeys(funcKey)
                        FOR EACH funcEntKey IN funcEntKeys:
                            IF binarySearch(user.resolvedEntitlements, funcEntKey) >= 0:
                                parentRole = findAncestorIn(funcEntKey, user.directAssignments)
                                writer.write(TSV: sodKey, accountKey, parentRole, funcKey, funcEntKey, 2, parentRole)

    writer.close()

    // Step 5: Bulk load via LOAD DATA LOCAL INFILE
    EXECUTE: LOAD DATA LOCAL INFILE 'tempFile'
             INTO TABLE sodrisk_entitlement_new_job
             FIELDS TERMINATED BY '\t'
             LINES TERMINATED BY '\n'
             (SODKEY, ACCOUNTKEY, ASSOCIATEDSAPROLEKEY, FUNCTIONKEY, TCODEKEY, SODTYPE, PARENTROLEKEYASCSV)

    DELETE tempFile
```

### LOAD DATA INFILE Approach

The key innovation in Phase 4 is using MySQL's `LOAD DATA LOCAL INFILE` instead of batch INSERT statements:

1. **Write phase:** All detail rows written to a temporary TSV file using a 1 MB buffered writer
2. **Load phase:** Single MySQL command bulk-loads the entire file

**Why this is faster:**
- Batch INSERT: MySQL parses SQL, validates constraints, updates indexes per-batch (even with rewriteBatchedStatements)
- LOAD DATA INFILE: MySQL reads raw data directly into the storage engine, minimal parsing, single index update pass

**Performance comparison (485K rows):**
- Batch INSERT (1000/batch): ~45 seconds
- LOAD DATA INFILE: ~1.3 seconds (35x faster)

### Data Structures

| Structure | Type | Purpose |
|-----------|------|---------|
| sodKeyMap | Map<String, Long> | Maps "user###risk###endpoint" → generated SODKEY |
| tempFile | File (TSV) | Intermediate storage for LOAD DATA INFILE |
| funcEntKeysCache | Map<Long, List<Long>> | Cached NonSAP function entitlement keys |

### Complexity

- **Summary rows:** O(violations) batch INSERTs
- **Detail rows:** O(violations × evidence_per_violation) file writes + O(1) LOAD DATA
- **Space:** O(detail_rows × ~50 bytes) for temp file

### Difference from Old System (ECMv4)

| Aspect | Old System | New System |
|--------|-----------|------------|
| Write method | Individual Hibernate save() per row | LOAD DATA INFILE (bulk) |
| Computation in write phase | Re-evaluates auth for detail rows | Zero computation (uses pre-collected evidence) |
| Detail row timing | Interleaved with evaluation | Pure I/O phase (all computation done in Phase 2) |
| Performance (485K rows) | Minutes | 1.3 seconds |

---

## Summary: End-to-End Complexity

| Phase | Time Complexity | Space Complexity | Hitachi Actual |
|-------|----------------|------------------|----------------|
| Phase 0 | O(F × C) | O(F × C) | 0.2 sec |
| Phase 1 | O(E + A × R) | O(E + A × R_resolved) | 32 sec |
| Phase 2 | O(U × F × C_avg) | O(F × U/64 + V × E_avg) | 27 sec |
| Phase 3 | O(Risks × U/64) | O(Risks × U/64) | < 1 sec |
| Phase 4 | O(V × E_avg) | O(V × E_avg) | 36 sec |
| **Total** | — | — | **1 min 47 sec** |

Where: F=functions, C=conditions, E=edges, A=accounts, R=roles, U=users, V=violations, E_avg=avg evidence per violation
