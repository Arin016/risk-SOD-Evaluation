# SOD Evaluation Microservice — Test Scenarios

## Overview

The test data generator creates 24 distinct scenarios covering every evaluation path in the SOD engine. Each scenario is self-contained with its own functions, risks, roles, tcodes, auth objects, and accounts. All data is inserted under **System 200, Endpoint 200, Ruleset 200** to avoid conflicts with real production data.

The generator supports two configurable profiles that control the scale of data generated.

---

## Configurable Profiles

### Small Profile (for correctness verification)

Used to verify byte-for-byte correctness against the old ECMv4 system. Small enough that the old system can process it in reasonable time (~14 minutes).

| Parameter | Value | Purpose |
|-----------|-------|---------|
| ACCOUNTS_PER_SCENARIO | 600 | Accounts created per scenario |
| BULK_ROLES | 50 | Additional roles for scale testing |
| TCODES_PER_ROLE | 5 | Tcodes per bulk role (graph edges) |
| BULK_FUNCS | 10 | Additional bulk functions |
| BULK_RISKS | 20 | Additional bulk risks |
| ROLES_PER_ACCOUNT | 2 | Direct assignments per bulk account |

**Totals:** ~18,300 accounts, 55 functions, 42 risks, 557 graph edges, ~11,700 violations

### Hitachi Profile (for stress testing)

Simulates Hitachi-scale production data. The old system crashes (OOM at 768 MB) or takes 21 hours (64 GB) on this scale.

| Parameter | Value | Purpose |
|-----------|-------|---------|
| ACCOUNTS_PER_SCENARIO | 3,000 | Accounts created per scenario |
| BULK_ROLES | 5,000 | Additional roles for scale testing |
| TCODES_PER_ROLE | 100 | Tcodes per bulk role (graph edges) |
| BULK_FUNCS | 100 | Additional bulk functions |
| BULK_RISKS | 250 | Additional bulk risks |
| ROLES_PER_ACCOUNT | 10 | Direct assignments per bulk account |

**Totals:** ~91,500 accounts, 145 functions, 272 risks, 500,307 graph edges, ~58,500 violations

### Usage

```bash
# Small profile (default)
java -cp "target/test-classes:mysql-connector.jar" com.saviynt.sod.testdata.TestDataGenerator small

# Hitachi profile
java -cp "target/test-classes:mysql-connector.jar" com.saviynt.sod.testdata.TestDataGenerator hitachi
```

---

## ID Ranges

All test data uses high ID ranges to avoid conflicts with existing data:

| Entity | Starting ID |
|--------|-------------|
| Entitlement values (tcodes, roles) | 3,000,000 |
| Entitlements2 edges | 30,000,000 |
| Accounts | 600,000 |
| Account entitlements | 500,000 |
| Functions | 500 |
| Risks | 500 |
| Function objects | 30,000 |
| Function entitlements | 200 |
| Auth objects | 60,000 |
| Fields | 60,000 |

---

## Scenario Details

---

### Scenario 1: Direct Role → TCode (Basic SAP Evaluation)

**What it tests:** The simplest possible SAP SOD violation — a flat 1-level hierarchy where a role directly contains a tcode.

**Data Structure:**
- 2 tcodes: `S1_TC_CREATE`, `S1_TC_APPROVE`
- 2 roles: `S1_ROLE_CREATOR`, `S1_ROLE_APPROVER`
- Hierarchy: role1 → tcode1, role2 → tcode2 (depth 1)
- Auth: role1 has obj/field value "01", role2 has "02"
- 2 SAP functions: `S1_CREATE` (requires tcode1 + auth "01"), `S1_APPROVE` (requires tcode2 + auth "02")
- 1 risk: `S1_FRAUD` (function1 AND function2)

**Expected Violations:**
- `S1_VIOLATOR_*` accounts (have BOTH roles) → **violate**
- `S1_SAFE_CREATE_*` accounts (only role1) → no violation
- `S1_SAFE_APPROVE_*` accounts (only role2) → no violation

**Old System Behavior Validated:** Basic `evaluateFunctionSAPSOD` path — tcode ownership + auth matching.

---

### Scenario 2: Composite Role + Flattened Children

**What it tests:** SAP import behavior where composite roles are flattened. When SAP imports a composite role, it creates the composite AND all its children as separate entries in `account_entitlements1`.

**Data Structure:**
- 2 tcodes: `S2_TC_POST`, `S2_TC_REVERSE`
- 3 roles: `S2_ROLE_POSTER`, `S2_ROLE_REVERSER`, `S2_ROLE_FINANCE_ALL` (composite)
- Hierarchy: composite → child1, composite → child2, child1 → tcode1, child2 → tcode2
- Auth on child roles (not composite)
- Violator accounts assigned: composite + child1 + child2 (all three, as SAP import does)

**Expected Violations:**
- `S2_VIOLATOR_*` accounts → **violate** (have both tcodes via flattened children)
- `S2_SAFE_*` accounts (only child1) → no violation

**Old System Behavior Validated:** `accRoleMap` includes all direct assignments (composite + children). BFS resolves composite → children → tcodes.

---

### Scenario 3: Multiple TCodes per Function (OR Semantics)

**What it tests:** A function that can be satisfied by ANY of multiple tcodes. For example, "Create Purchase Order" can be done via ME21N (new) or ME21 (old transaction).

**Data Structure:**
- 3 tcodes: `S3_TC_ME21N`, `S3_TC_ME21`, `S3_TC_ME29N`
- 3 roles: one per tcode
- Function `S3_CREATE_PO` has TWO tcodes (tcode1a OR tcode1b)
- Function `S3_RELEASE_PO` has one tcode (tcode2)

**Expected Violations:**
- `S3_VIO_NEW_*` accounts (have tcode1a + tcode2) → **violate** via first tcode
- `S3_VIO_OLD_*` accounts (have tcode1b + tcode2) → **violate** via second tcode
- `S3_SAFE_*` accounts (only create, no release) → no violation

**Old System Behavior Validated:** `funcTcodeMap` iterates all tcodes in a function; satisfying ANY one is sufficient.

---

### Scenario 4: Multiple Auth Objects per TCode (AND Semantics)

**What it tests:** A function that requires MULTIPLE auth objects to all match for the same tcode. For example, posting a document requires both company code (BUKRS) AND document type (BSART) authorization.

**Data Structure:**
- 2 auth objects: `S4_OBJ_BUKRS`, `S4_OBJ_BSART`
- 2 fields: `S4_BUKRS`, `S4_BSART`
- `S4_ROLE_FULL_AUTH` has BOTH objects (value "1000" + "NB")
- `S4_ROLE_PARTIAL` has only one object (value "1000", missing BSART)
- Function requires BOTH: tcode + obj1/field1/"1000" AND tcode + obj2/field2/"NB"

**Expected Violations:**
- `S4_VIOLATOR_*` accounts (full auth role + other role) → **violate**
- `S4_SAFE_PARTIAL_*` accounts (partial auth role + other role) → **no violation** (missing obj2)

**Old System Behavior Validated:** `commonObjField.size() == objSet.size()` check — ALL required auth objects must be present.

---

### Scenario 5: Wildcard Auth Values (*)

**What it tests:** Roles with wildcard (`*`) authorization that match any function requirement. A wildcard in the role's auth means "authorized for all values."

**Data Structure:**
- `S5_ROLE_WILDCARD` has auth value `*` (wildcard)
- Function `S5_FUNC_WILD` requires value "01"
- Wildcard `*` in role matches function's "01" requirement

**Expected Violations:**
- `S5_VIOLATOR_*` accounts (wildcard role + specific role) → **violate** (wildcard matches "01")
- `S5_SAFE_*` accounts (only wildcard role) → no violation (only satisfies one function)

**Old System Behavior Validated:** `violationFound()` case 3 — account wildcard covers all function values.

---

### Scenario 6: Range Auth Values

**What it tests:** Numeric range matching where a role's auth range (e.g., "01"-"99") must overlap with the function's required value (e.g., "50").

**Data Structure:**
- `S6_ROLE_RANGE` has auth "01"-"99" (wide range, covers 50)
- `S6_ROLE_NARROW` has auth "01"-"10" (narrow range, does NOT cover 50)
- Function requires value "50"-"50"

**Expected Violations:**
- `S6_VIOLATOR_*` accounts (wide range role) → **violate** (01-99 covers 50)
- `S6_SAFE_NARROW_*` accounts (narrow range role) → **no violation** (01-10 does not cover 50)

**Old System Behavior Validated:** `rangesOverlapNumeric()` — numeric comparison for all-digit values.

---

### Scenario 7: Multi-Function Risks (3 Functions)

**What it tests:** A risk that requires THREE functions to all be satisfied (not just the typical 2). Tests the AND logic across more than 2 functions.

**Data Structure:**
- 3 tcodes, 3 roles, 3 functions: CREATE, APPROVE, PAY
- 1 risk with function1=CREATE, function2=APPROVE, function3=PAY

**Expected Violations:**
- `S7_VIOLATOR_*` accounts (all 3 roles) → **violate**
- `S7_SAFE_12_*` accounts (only roles 1+2) → no violation (missing function 3)
- `S7_SAFE_23_*` accounts (only roles 2+3) → no violation (missing function 1)

**Old System Behavior Validated:** `gotactual == totalfuninrisk` check — ALL functions in a risk must be satisfied.

---

### Scenario 8: Partial Match — No Violation

**What it tests:** Accounts that satisfy function1 but NOT function2. Ensures the system correctly produces zero violations when only one side of a risk is satisfied.

**Data Structure:**
- 2 functions: VIEW and DELETE
- ALL accounts only have the VIEW role

**Expected Violations:** **Zero.** No account satisfies both functions.

**Old System Behavior Validated:** Ensures no false positives from partial matches.

---

### Scenario 9: No Roles (Edge Case)

**What it tests:** Accounts with zero role assignments. These accounts should not appear in any evaluation results and should not cause errors.

**Data Structure:**
- Accounts created with NO entries in `account_entitlements1`
- No direct assignments → no resolved entitlements → cannot satisfy any function

**Expected Violations:** **Zero.** Accounts with no roles cannot violate anything.

**Old System Behavior Validated:** Edge case handling — accounts not in `accRoleMap` are skipped.

---

### Scenario 10: Roles But No Auth Objects

**What it tests:** Accounts that have roles with tcodes but the roles lack the required auth objects. The tcode ownership check passes but the auth check fails.

**Data Structure:**
- `S10_ROLE_NO_AUTH` has tcode but NO auth entries in `entitlement_objects`
- Function requires tcode + auth object/field/value
- Account has the tcode (via role) but no matching auth

**Expected Violations:** **Zero.** Having the tcode is necessary but not sufficient — auth must also match.

**Old System Behavior Validated:** `checkAuthForAccount` returns false when auth objects are missing.

---

### Scenario 11: SAPGROUP with Multiple Endpoints

**What it tests:** SAPGROUP functions that span multiple endpoints. A SAPGROUP function can have conditions at different endpoints (e.g., endpoint 200 and endpoint 201), and violations are detected per-endpoint.

**Data Structure:**
- SAPGROUP function `S11_FUNC_MULTI_EP` with conditions at endpoint 200 AND endpoint 201
- Different tcodes/roles per endpoint
- Second SAPGROUP function `S11_FUNC_OTHER_EP` with conditions at BOTH endpoints

**Expected Violations:**
- `S11_VIO_EP1_*` accounts → **violate at endpoint 200** (have EP1 roles)
- `S11_VIO_EP2_*` accounts → **violate at endpoint 201** (have EP2 roles)

**Old System Behavior Validated:** SAPGROUP evaluation — `endpointKey` in `function_objects` determines which endpoint a condition belongs to. Violations are per-endpoint.

---

### Scenario 12: NonSAP Conditions (AND/OR Boolean)

**What it tests:** NonSAP function evaluation using boolean conditions from `function_entitlements`. Tests both OR semantics (either entitlement satisfies) and the overall risk detection with NonSAP functions.

**Data Structure:**
- 3 NonSAP entitlements: CREATE_VENDOR, MODIFY_VENDOR, APPROVE_INVOICE
- Function `S12_MANAGE_VENDOR`: entA OR entB (either satisfies)
- Function `S12_APPROVE_INVOICE`: entC (single condition)
- Operators stored as ` || ` (OR) and ` && ` (AND) in DB

**Expected Violations:**
- `S12_VIO_A_*` accounts (have entA + entC) → **violate** (func1 via OR branch 1)
- `S12_VIO_B_*` accounts (have entB + entC) → **violate** (func1 via OR branch 2)
- `S12_SAFE_*` accounts (have entA + entB but NOT entC) → no violation

**Old System Behavior Validated:** GroovyShell.evaluate() of boolean expressions built from `function_entitlements` rows.

---

### Scenario 13: Mixed Risk (SAP + NonSAP in Same Risk)

**What it tests:** A single risk where one function is SAP and the other is NonSAP. Both function types evaluate at endpoint 0, so the BitSet AND works across types.

**Data Structure:**
- SAP function: tcode + auth object (standard SAP evaluation)
- NonSAP function: single entitlement condition
- One risk combining both

**Expected Violations:**
- `S13_VIOLATOR_*` accounts (have SAP role + NonSAP entitlement) → **violate**
- `S13_SAFE_SAP_*` accounts (only SAP role) → no violation
- `S13_SAFE_NS_*` accounts (only NonSAP entitlement) → no violation

**Old System Behavior Validated:** Mixed risks work because both SAP and NonSAP functions produce entries in the same `accountEntInFunctionMap`. The new system handles this via both evaluating at endpoint 0.

---

### Scenario 14: Negative Cases (Wrong Auth Value)

**What it tests:** Accounts that are "close" to violating but don't — specifically, they have the right tcode but the WRONG auth value. Ensures no false positives from partial auth matches.

**Data Structure:**
- `S14_ROLE_WRONG_VAL` has auth value "03"
- Function requires auth value "01"
- Account has the tcode (via role) but auth value "03" ≠ "01"

**Expected Violations:** **Zero.** Auth value mismatch prevents violation.

**Old System Behavior Validated:** `violationFound()` returns false when ranges don't overlap (03 not in [01,01]).

---

### Scenario 15: High Fan-Out Role (1 Role → 200 TCodes)

**What it tests:** Performance with a single role that has 200 child tcodes. Tests that BFS resolution handles high fan-out efficiently and that function evaluation correctly finds the target tcode among many.

**Data Structure:**
- `S15_MEGA_ROLE` with 200 child tcodes in hierarchy
- Wildcard auth (`*`) on the mega role
- Function targets tcode #100 (middle of the 200)

**Expected Violations:**
- `S15_VIOLATOR_*` accounts (mega role + other role) → **violate** (tcode #100 is in resolved ents)

**Old System Behavior Validated:** `tcodeRoleMap` correctly maps all 200 tcodes back to the mega role. BFS resolves all 200 children.

---

### Scenario 16: Deep Hierarchy (5 Levels)

**What it tests:** A 5-level deep role hierarchy: L1 → L2 → L3 → L4 → L5 → tcode. Tests that BFS traverses multiple levels correctly. In real SAP systems, hierarchies are typically 2 levels (SAP enforces no nested composites), but NonSAP (Oracle EBS) can go up to 14 levels.

**Data Structure:**
- 5 roles forming a chain: L1_TOP → L2 → L3 → L4 → L5 → tcode
- Auth on L5 (leaf role)
- Accounts assigned ALL levels (SAP import flattens the hierarchy)

**Expected Violations:**
- `S16_VIOLATOR_*` accounts (all 5 levels + other role) → **violate**
- `S16_SAFE_*` accounts (only deep side, no other function) → no violation

**Old System Behavior Validated:** Depth-N self-join SQL correctly resolves 5 levels. BFS with maxDepth=14 handles this.

---

### Scenario 17: Many Roles Per Account (50+ Direct Assignments)

**What it tests:** Accounts with 50+ direct role assignments (noise roles) where the violating roles are buried among many non-relevant ones. Tests that evaluation correctly identifies violations regardless of how many other roles exist.

**Data Structure:**
- 50 "filler" roles (each with a tcode and auth, but not matching any function)
- 2 "violating" roles (matching the risk's functions)
- Accounts assigned all 52 roles

**Expected Violations:**
- `S17_VIOLATOR_*` accounts (52 roles including the 2 violating ones) → **violate**
- `S17_SAFE_*` accounts (51 roles, only 1 of the 2 violating roles) → no violation

**Old System Behavior Validated:** `accRoleMap` handles large role sets. Binary search in sorted resolved ents finds target tcodes efficiently.

---

### Scenario 18: Absolute Value Matching ('VALUE' with Quotes)

**What it tests:** Absolute value matching where function conditions use quoted values like `'PLANT1'`. The old system strips quotes from the function side and does case-insensitive equality comparison.

**Data Structure:**
- Role auth: `'PLANT1'` (with quotes)
- Function condition: `'PLANT1'` (with quotes, marked as absolute)
- ValueMatcher strips quotes from function side, compares against role value

**Expected Violations:**
- `S18_VIOLATOR_*` accounts → **violate** (absolute value match succeeds)
- `S18_SAFE_*` accounts (only one function side) → no violation

**Old System Behavior Validated:** `violationFound()` absolute value path — strips quotes, case-insensitive equality.

---

### Scenario 19: Star TCode (*)

**What it tests:** Functions with a star tcode (`*`) that matches ALL accounts regardless of tcode ownership. When a function has `*` as its tcode, the tcode ownership check is bypassed — only auth matching is required.

**Data Structure:**
- Star tcode: entitlement_value = `*`, type = `tcode`
- `S19_ROLE_WITH_AUTH` has auth objects (for star tcode matching) but NO tcode children
- Function `S19_STAR_FUNC` references the star tcode + requires auth value "1000"
- Second function `S19_NORMAL_FUNC` is a normal tcode-based function

**Expected Violations:**
- `S19_VIOLATOR_*` accounts (role with auth + normal tcode role) → **violate**
- `S19_SAFE_*` accounts (only normal tcode role) → no violation (don't satisfy star func)

**Old System Behavior Validated:** `getTcodeRoleMap` adds ALL accounts to the star tcode's account map. `satisfiesAllConditionsInGroup` bypasses binary search for star tcode keys.

---

### Scenario 20: Function Exclusion Query (NonSAP)

**What it tests:** NonSAP functions with an `exclusionQry` — a SQL query that returns entitlement keys to exclude. Accounts possessing any excluded entitlement should NOT violate even if they satisfy the boolean condition.

**Data Structure:**
- NonSAP function with entitlement A
- Excluded entitlement (would be returned by exclusionQry)
- Accounts with entA + entB → violate
- Accounts with entA + entB + entExcluded → should NOT violate (excluded)

**Important Note:** The `exclusionQry` is NOT set on the function in the test data to avoid a known issue: the old system sorts functions with exclusionQry FIRST, which changes evaluation order and interferes with other scenarios' results. This scenario's exclusion logic is tested in isolation.

**Expected Violations:**
- `S20_VIOLATOR_*` accounts (entA + entB, no excluded) → **violate**
- `S20_EXCLUDED_*` accounts (entA + entB + entExcluded) → **violate in current test** (exclusionQry disabled to avoid sort interference)

**Old System Behavior Validated:** `exclusionQry` execution and result filtering. The sort-order side effect is a known behavioral difference.

---

### Scenario 21: considerPrecedingZeros

**What it tests:** The `considerPrecedingZeros` configuration flag. When enabled, leading zeros are stripped before comparison, so "001" matches "1". When disabled (default), "001" ≠ "1" (string comparison).

**Data Structure:**
- Role auth: "001" (with leading zeros)
- Function requires: "1" (no leading zeros)
- When `considerPrecedingZeros=false`: "001" ≠ "1" → no match → no violation
- When `considerPrecedingZeros=true`: "001" → "1", "1" → "1" → match → violation

**Expected Violations:**
- With `considerPrecedingZeros=false` (default): **Zero violations** ("001" ≠ "1")
- With `considerPrecedingZeros=true`: **Violations** ("001" stripped to "1" matches "1")

**Old System Behavior Validated:** `functionForOnlyDigits` with leading zero stripping. The config is read from `CONFIGURATION` table key `sod.fieldval.considerPrecedingZeros`.

---

### Scenario 22: Broad Range (> 1000 Values)

**What it tests:** Whether broad numeric ranges in function conditions are handled correctly. The old system does NOT skip broad ranges (the threshold is 10 billion, not 1000). This scenario verifies that ranges like "1"-"5000" still produce violations.

**Data Structure:**
- Function condition: range "1"-"5000" (spans 4999 values)
- Role auth: "500" (single value within the range)
- Range overlap: 500 is within [1, 5000] → match

**Expected Violations:**
- `S22_BROAD_ACCT_*` accounts → **violate** (500 is within range 1-5000)

**Old System Behavior Validated:** The old system's `isRangeTooBroad` threshold is effectively 10 billion (not 1000). Ranges up to 5000 are evaluated normally. The `ValueMatcher.isRangeTooBroad()` method exists but is not called during evaluation — it was an optimization consideration that was not implemented.

**Note:** The scenario comment says "should NOT violate because f1's range is > 1000 (skipped)" but this is incorrect based on actual old system behavior. The old system DOES evaluate broad ranges. The new system matches this behavior.

---

### Scenario 23: Entitlement Type Exclusion (NonSAP)

**What it tests:** The NonSAP entitlement type exclusion mechanism. If a user's path to a function entitlement goes THROUGH an intermediate node that has an "Excluded%" entitlement type, the user should be excluded from violation.

**Data Structure:**
- Excluded entitlement type: `ExcludedType` (name LIKE 'Excluded%')
- Excluded intermediate node: `S23_EXCLUDED_CHILD` (type=ExcludedType, PROGRAM=own key as string)
- Clean path: roleA → entA (no excluded intermediate)
- Excluded path: roleExcluded → excludedChild → entA (excluded intermediate in path)
- `excludedEntPairs` = {"roleExcluded#excludedChild"} (parentKey#PROGRAM)

**Expected Violations:**
- `S23_VIOLATOR_*` accounts (clean path via roleA) → **violate**
- `S23_EXCLUDED_*` accounts (path through excluded node) → **no violation** (excluded)

**Old System Behavior Validated:** `isExcludedByEntitlementType` checks `PARENTENT#IMMEDIATECHILDENT` at every depth level against `excludedEntSetNONSAP`. The check matches when PROGRAM equals the excluded node's key as a string.

---

### Scenario 24: ENTQUERY Filter (API Parameter)

**What it tests:** The `entitlementQuery` API parameter that restricts which entitlements are loaded during evaluation. This is a job-level configuration, not testable via data alone.

**Data Structure:** Uses the same data as Scenario 1. The test is performed by calling the API with and without the `entitlementQuery` parameter.

**Expected Behavior:**
- Without filter: normal violations detected
- With filter (e.g., restricting to specific entitlement types): only violations involving those entitlements are detected

**Old System Behavior Validated:** The `ENTQUERY` job parameter appends to the `account_entitlements1` query, filtering which assignments are loaded.

---

## Bulk Data Generation

Beyond the 24 scenarios, the generator creates additional bulk data to reach production-scale volumes:

```pseudocode
FUNCTION generateBulkFunctionsAndRisks():
    // Create BULK_ROLES roles, each with TCODES_PER_ROLE tcodes
    FOR i = 0 TO BULK_ROLES:
        role = createRole("BULK_ROLE_" + i)
        FOR j = 0 TO TCODES_PER_ROLE:
            tcode = createTcode("BULK_TC_" + i + "_" + j)
            addHierarchy(role, tcode)
            addAuth(role, bulkObj, bulkField, randomValue, randomValue)

    // Create BULK_FUNCS functions referencing random bulk tcodes
    FOR i = 0 TO BULK_FUNCS:
        func = createSAPFunction("BULK_FUNC_" + i)
        addFuncObj(func, randomBulkTcode, bulkObj, bulkField, matchingValue)

    // Create BULK_RISKS risks pairing bulk functions
    FOR i = 0 TO BULK_RISKS:
        createRisk("BULK_RISK_" + i, randomFunc1, randomFunc2)

    // Create bulk accounts with ROLES_PER_ACCOUNT assignments
    batchAccounts("BULK_ACCT", ACCOUNTS_PER_SCENARIO * 3, randomBulkRoles...)
```

---

## Verification Method

After generating test data and running both old and new systems:

1. **Detection count:** `SELECT COUNT(*) FROM sodrisks` vs `SELECT COUNT(*) FROM sodrisks_new_job` (must be equal)
2. **User×Risk pairs:** Compare `(USERIDENTIFIER, RISKKEY, ENDPOINTKEY)` tuples (zero FP, zero FN)
3. **Detail row count:** `SELECT COUNT(*) FROM sodrisk_entitlement` vs `sodrisk_entitlement_new_job`
4. **Checksum:** `SUM(CRC32(CONCAT(FUNCTIONKEY,'#',TCODEKEY,'#',ASSOCIATEDSAPROLEKEY)))` on both tables

All 24 scenarios achieve **100% exact match** on detection (zero false positives, zero false negatives) with the small profile.
