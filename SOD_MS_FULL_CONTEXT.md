# SOD Evaluation Microservice — Full Context Document

## Project Overview

Building a **from-scratch SOD (Segregation of Duties) evaluation microservice** to replace the existing ECMv4 Grails-based `RiskSODEvaluationJob`. The new system must produce **byte-for-byte identical results** to the old system while being orders of magnitude faster and using less memory.

**Owner:** Arin Mallanna (Backend Dev, Saviynt)
**Senior:** Niranjan Patil (will review code)
**Repo:** `/Users/arin.mallanna/AAG/sod-evaluation-service`
**Old System:** `/Users/arin.mallanna/AAG/ECMG66/ecmv4` (Grails/Groovy, 7635-line `RiskSODEvaluationService.groovy`)

---

## Architecture

### New System Pipeline (5 Phases)

```
Phase 0: Load config (risks, functions, conditions, star tcodes, exclusion pairs)
Phase 1: Load graph (ENTITLEMENTS2) + resolve user access via BFS + build reverse graph
Phase 2: Evaluate functions → BitSets (parallel, virtual threads) + collect evidence
Phase 3: Detect violations → BitSet AND across risk functions
Phase 4: Write violations + detail rows to DB (using pre-computed evidence from Phase 2)
Phase 5: Close stale violations (TODO)
```

### Key Design Decisions

1. **BFS graph traversal** replaces old system's depth-N self-join SQL queries
2. **BitSet per function** — O(users/64) for violation detection via AND
3. **Evidence collected during Phase 2** — Phase 4 is pure DB writes, zero computation
4. **Reverse graph** (child→parents) built at load time for O(1) ancestor lookups
5. **Virtual threads** for parallel function evaluation

---

## Benchmarks Achieved

### Test Data (24 scenarios, 18K accounts, 55 functions, 42 risks)
- Old system: ~785 sec (13 min)
- New system: **27 sec** (detection) + DB writes
- **correct=true, matches=11,700, FP=0, FN=0**
- Detail rows: **25,800 = 25,800** (exact match)

### Real Production Data — System 5 (309 users, 108 functions, 205 risks)
- Old system: 334 sec
- New system: **40 sec total** (6 sec detection + 34 sec DB writes)
- **correct=true, matches=14,806, FP=0, FN=0**
- Memory: 468 MB (512 MB heap) vs old system's 595 MB (768 MB heap)

### Hitachi-Scale (72K accounts, 135 functions, 267 risks, 500K graph edges)
- Old system: **OOM in 4 seconds** (768 MB) / 21 hours on prod (64 GB)
- New system: **1 min 52 sec**, 858 MB peak (1 GB heap)
- 42,000 violations

---

## Test Data Generator

**Location:** `src/test/java/com/saviynt/sod/testdata/TestDataGenerator.java` + `ScenarioBuilder.java`

**Usage:**
```bash
# Small config (for correctness verification with old system)
java -cp "target/test-classes:mysql-connector.jar" com.saviynt.sod.testdata.TestDataGenerator small

# Hitachi-scale (stress test, old system will OOM)
java -cp "target/test-classes:mysql-connector.jar" com.saviynt.sod.testdata.TestDataGenerator hitachi
```

**Profiles:**
- `small`: 600 accounts/scenario, 50 bulk roles, 5 tcodes/role, 10 bulk funcs, 20 bulk risks, 2 roles/account
- `hitachi`: 3000 accounts/scenario, 5000 bulk roles, 100 tcodes/role, 100 bulk funcs, 250 bulk risks, 10 roles/account

**All data goes to:** System 200, Endpoint 200, Ruleset 200

---

## 24 Test Scenarios

| # | Scenario | What it tests |
|---|----------|---------------|
| 1 | Direct role → tcode | Flat 1-level hierarchy |
| 2 | Composite role + flattened children | Role contains sub-roles, all flattened |
| 3 | Multiple TCodes per function (OR) | Any tcode satisfies |
| 4 | Multiple auth objects per TCode (AND) | All auth must match |
| 5 | Wildcard auth values (`*`) | Matches everything |
| 6 | Range auth values (01-99) | Numeric range matching |
| 7 | Multi-function risks (3 functions) | Risk with 3+ functions |
| 8 | Partial match — no violation | Satisfies func1 but not func2 |
| 9 | No roles (edge case) | Accounts with zero assignments |
| 10 | Roles but no auth objects | Partial match |
| 11 | Multiple endpoints (SAPGROUP) | Functions spanning 2 endpoints |
| 12 | NonSAP conditions (AND/OR boolean) | Non-SAP evaluation |
| 13 | Mixed risks (SAP + NonSAP) | Single risk with both types |
| 14 | Negative cases | Close but don't violate |
| 15 | High fan-out role | 1 role → 200 tcodes |
| 16 | Deep hierarchy | 5 levels in entitlements2 |
| 17 | Many roles per account | 50+ direct assignments |
| 18 | Absolute value matching | Quoted 'VALUE' exact match |
| 19 | Star TCode (`*`) | Function with `*` tcode matches all accounts with auth |
| 20 | Function Exclusion Query | NonSAP exclusionQry (code done, test pending) |
| 21 | considerPrecedingZeros | "001" matches "1" when enabled |
| 22 | Range > 1000 | Old system does NOT skip broad ranges (threshold is 10B) |
| 23 | Entitlement Type Exclusion | NonSAP path-level exclusion via 'Excluded%' type |
| 24 | ENTQUERY filter | API param test |

---

## Key Code Changes (Fixes Made)

### 1. Star TCode (`*`)
- **File:** `SodConfigDao.java` — `loadStarTcodeKeys()`
- **File:** `FunctionEvaluationService.java` — skips binary search for star tcode keys in `satisfiesAllConditionsInGroup`
- **File:** `EvaluationOrchestrator.java` — loads and passes star tcode keys

### 2. Numeric Value Comparison
- **File:** `ValueMatcher.java` — `rangesOverlap()` uses string comparison by default; `rangesOverlapNumeric()` only when `considerPrecedingZeros=true`
- **Important:** Old system uses string comparison for ranges. Only does numeric when expanding ranges into individual values.

### 3. Function Exclusion Query (NonSAP)
- **File:** `SodConfigDao.java` — `loadFunctionExcludedEnts(exclusionQry)`
- **File:** `FunctionEvaluationService.java` — `evaluateNonSAP` accepts `excludedFuncEnts`, skips users with excluded ents
- **Note:** `exclusionQry` on a function causes old system to sort that function first, which can interfere with other scenarios

### 4. Entitlement Type Exclusion (NonSAP)
- **File:** `SodConfigDao.java` — `loadExcludedEntPairs()` returns `Set<String>` of "parentKey#PROGRAM" pairs
- **File:** `FunctionEvaluationService.java` — `walkAndCheckExcluded()` does full-depth graph walk checking every intermediate node
- **Key insight:** Old system checks `PARENTENT#IMMEDIATECHILDENT` (Long keys) against `PARENTENT#PROGRAM` (key#string). Only matches when PROGRAM equals the excluded node's key as string.

### 5. FUNCTION1KEY IS NULL
- **File:** `SodConfigDao.java` — `loadActiveRisks` adds `AND FUNCTION1KEY IS NOT NULL`
- **Reason:** Old system's `getFinalMap` starts with function1. If NULL, `finalMap` stays empty, all subsequent functions skipped.

### 6. Evidence Collection During Phase 2
- **File:** `FunctionEvidence.java` — record with `accountKey, tcodeKey, assocSapRole, directRole`
- **File:** `FunctionEvaluationService.java` — `evaluateSAPWithEvidence()` + `collectSAPEvidence()` + `setGraphRefs()`
- **File:** `EvaluationOrchestrator.java` — passes `evidenceMap` to Phase 2, reads it in Phase 4
- **Result:** Phase 4 went from 7+ min → 22 sec

### 7. Reverse Graph
- **File:** `AccessGraphService.java` — `reverseGraph` (child→parents) built during `loadGraph()`
- **Methods:** `getImmediateParents()`, `getReverseGraph()`, `findAncestorIn()`

---

## Old System Behaviors Replicated

1. **accRoleMap built once per security system** — not per function
2. **Function sort order** — functions with exclusionQry sorted first
3. **Star tcode** — adds ALL accounts to tcodeAccountMap for `*` tcode
4. **SAP hierarchy max depth = 2** (SAP enforces no nested composites)
5. **NonSAP hierarchy can be up to 14 levels** (Oracle EBS)
6. **Suspended accounts excluded** — `STATUS <> 'SUSPENDED FROM IMPORT SERVICE'`
7. **Detail rows:** one per tcode per function per violation (SAP), one per matching entitlement (NonSAP)
8. **SAPGROUP + SAP/NonSAP in same risk** — only works on endpoint 0 for SAP/NonSAP, SAPGROUP on its own endpoint

---

## Known Remaining Items

| # | Item | Status |
|---|------|--------|
| 1 | S20 Function Exclusion Query test | Code done, test data has exclusionQry removed to avoid sort interference |
| 2 | SOD_NONSAP_ORG_CALCULATION | Not implemented (Oracle EBS org-scoped NonSAP) |
| 3 | Hitachi-scale re-run with latest code | Pending |
| 4 | Detail row content verification on system 5 | Pending (counts match, values not spot-checked) |
| 5 | Phase 4 optimization for Hitachi scale | Evidence approach should work, needs verification |
| 6 | `considerPrecedingZeros=true` test | S21 scenario exists but config is false in our DB |

---

## How to Run

### New System
```bash
cd /Users/arin.mallanna/AAG/sod-evaluation-service
./mvnw spring-boot:run -Dspring-boot.run.jvmArguments="-Xmx512m" 2>&1 | tee /tmp/sod-eval.log

# Test data (system 200)
curl -s -X POST http://localhost:9220/sod-eval/api/v1/evaluate/sync -H "Content-Type: application/json" -d '{"rulesetKeys": [200], "securitySystemId": 200}'

# Real data (system 5)
curl -s -X POST http://localhost:9220/sod-eval/api/v1/evaluate/sync -H "Content-Type: application/json" -d '{"rulesetKeys": [1], "securitySystemId": 5}'
```

### Old System (ECMv4)
- Run from IntelliJ with VM options: `-Xms768m -Xmx12g`
- Trigger from UI: Job Control Panel → TEST_BENCH_RULESET_NEWSODEVAL → Run Now
- Config: RULESETKEY=200, SECURITYSYSTEMS=200 (test data) or RULESETKEY=1, SECURITYSYSTEMS=5 (real data)
- Job name for system 5: `SAP_SAP_BENCH`

### Verify Results
```sql
-- Compare violation counts
SELECT 'old' as sys, COUNT(*) FROM sodrisks WHERE RISKKEY IN (SELECT RISKID FROM risks WHERE RULESETKEY=200)
UNION ALL SELECT 'new', COUNT(*) FROM sodrisks_new_job WHERE RISKKEY IN (SELECT RISKID FROM risks WHERE RULESETKEY=200);

-- Compare detail row counts
SELECT 'old' as sys, COUNT(*) FROM sodrisk_entitlement WHERE SODKEY IN (SELECT SODKEY FROM sodrisks WHERE RISKKEY IN (SELECT RISKID FROM risks WHERE RULESETKEY=200))
UNION ALL SELECT 'new', COUNT(*) FROM sodrisk_entitlement_new_job WHERE SODKEY IN (SELECT SODKEY FROM sodrisks_new_job WHERE RISKKEY IN (SELECT RISKID FROM risks WHERE RULESETKEY=200));
```

---

## Database Tables

### Our tables (new system writes here)
- `sodrisks_new_job` — violation summary (one row per user×risk×endpoint)
- `sodrisk_entitlement_new_job` — violation detail (one row per tcode/entitlement per function)

### Old system tables (read for validation)
- `sodrisks` — old system's violations
- `sodrisk_entitlement` — old system's detail rows

### Key data tables
- `entitlements2` — role hierarchy graph (parent→child edges)
- `account_entitlements1` — direct account→role assignments
- `entitlement_objects` — auth data (role→object→field→value)
- `function_objects` — SAP function conditions (tcode + auth requirements)
- `function_entitlements` — NonSAP function conditions (boolean AND/OR)
- `risks` — risk definitions (function1key through function5key)
- `configuration` — runtime config (considerPrecedingZeros, etc.)

---

## Niranjan's Feedback & Direction

- Explore Go/Python (conclusion: Java is best for this workload — graph traversal not matrix math)
- Use libs where possible instead of custom logic
- Think about numpy/scikit (not applicable — our workload is branchy graph traversal)
- Java Spring Boot is fine, but explore alternatives for comparison
- Code will be reviewed over weekend/Monday

---

## Future Optimizations (CP-level)

1. **Role-level dedup** — group users by role signature, evaluate once per unique combo (5-15x speedup)
2. **Columnar BitSets** — per-entitlement BitSets for O(n/64) function evaluation
3. **Incremental updates** — only re-evaluate changed users/functions
4. **Phase 4 for Hitachi** — evidence approach should work (7,932 entries for 309 users → ~50K entries for 72K users = still tiny)

---

## Detail Row Parity Status (System 5 — UNRESOLVED)

**Current state:** Detection is 100% correct. Detail rows have structural differences:
- Old system: 485,353 rows
- New system: 493,257 rows  
- Old rows missing in new: 56,995
- New rows not in old: 64,899
- This is NOT a superset — different rows are written

**Root cause partially identified:** The old system's detail row logic in `evaluateFunctionSAPSOD` (lines 4900-5080):
1. `funcTcodeMap` is built from `function_objects` where `fieldkey != TCD_field_id` (field 65 = "TCD")
2. `funcTcodeMap` key = tcode, value = {tcode} (each tcode maps to itself)
3. For each tcode in `funcTcodeMap`, checks `tcodeRoleMap.get(tcode)` — if null, skip
4. Then `commonRoleswithTcode = intersection(accRoleMap.get(accountkey), tcodeRoleMap.get(tcode))`
5. For each role in `commonRoleswithTcode`, writes one detail row

**Key unknowns still to investigate:**
- Why `tcodeRoleMap` has entries for only 6 of 113 tcodes for user -120/function 27 when the DB query should return all 113
- The exact relationship between `funcTcodeMap.each` iteration and the `checkAuthForAccount` call that precedes it
- Whether the outer loop `funcTcodeMap.each` at line 4907 is the SAME map as what's populated at line 4266, or if it's been filtered by the auth evaluation
- The `commonObjField.size() == objSet.size()` check at line 4980 — this gates entry into the tcodeSet.each block. If auth doesn't pass for a tcode's objects, the block is never entered for that tcode

**Most likely root cause:** The `funcTcodeMap.each` at line 4907 iterates ALL tcodes, but the `commonObjField.size() == objSet.size()` check at line 4980 only passes for tcodes where ALL required auth objects match. So detail rows are only written for tcodes where the FULL auth check passes (all objects + fields match), not just "any auth match."

**Next steps:** 
1. Implement FULL auth check (all objects AND all fields must match for the tcode) in evidence collection
2. This should give us exactly 485,353 rows
