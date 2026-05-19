# SOD Evaluation Algorithm — From Scratch to Full Depth

This document explains how the SOD evaluation engine determines whether a user violates a Segregation of Duties risk. It is written to be validated by someone who understands the old ECMv4 system and the underlying SAP/NonSAP data model.

---

## 1. The Problem Statement

A **Risk** says: *"It is dangerous for one person to be able to both CREATE and APPROVE a Purchase Order."*

Formally:
- Risk R has two Functions: `F_CREATE_PO` and `F_APPROVE_PO`
- A user **violates** Risk R if they satisfy **F_CREATE_PO AND F_APPROVE_PO simultaneously**
- A user **satisfies** a Function if their entitlements meet the function's conditions

The engine's job: for every (user, risk) pair across potentially 91,000 users and 272 risks, determine who violates what — and collect the evidence (which roles/tcodes caused it).

---

## 2. The Five-Phase Pipeline

```
Phase 0 — Load config
  Load all Risks, Functions, Entitlement definitions, Role hierarchy
  (~10 DB queries total, everything into memory)

Phase 1 — Resolve "who has what"
  For each user, BFS through role hierarchy
  → resolvedEntitlements: sorted long[] of all roleKeys + tcodeKeys the user has

Phase 2 — Evaluate functions (parallel)
  For each function, for each user: does this user satisfy this function?
  → one BitSet per function (bit i = user i satisfies it)
  → evidence collected here too

Phase 3 — Detect violations
  For each risk: AND its function BitSets
  → users with bit set in ALL function BitSets are violators

Phase 4 — Persist
  Write violations + detail rows via LOAD DATA INFILE (bulk)
```

Phases 0–1 run once. Phase 2 runs in parallel (one virtual thread per function). Phases 3–4 run after all functions complete.

---

## 3. Core Data Structures

### 3.1 resolvedEntitlements — what a user has

After BFS through the role hierarchy, every user has:

```
resolvedEntitlements: long[] (sorted, ascending)

Example for user "arin":
  [1001, 1002, 3001, 3002, 3003, 3004]
   ↑     ↑     ↑     ↑     ↑     ↑
  role  role  tcode tcode tcode tcode
```

Roles and tcodes share the same keyspace (both are `long` primary keys from the DB). The array is sorted so all lookups are O(log n) binary search.

This array is the **single source of truth** for what a user can access. It is built once per user in Phase 1 and reused across all function evaluations in Phase 2.

### 3.2 BitSet — who satisfies a function

For each function, one `java.util.BitSet` is maintained:

```
Function F_APPROVE_PO:
  bit 0 = user 0 satisfies F_APPROVE_PO? (0 or 1)
  bit 1 = user 1 satisfies F_APPROVE_PO?
  ...
  bit N = user N satisfies F_APPROVE_PO?
```

With 91,000 users this is ~11 KB per function. 272 functions × 11 KB = ~3 MB total. The old system stored full Java objects per user per function — megabytes per function.

### 3.3 Violation detection — BitSet AND

Once all functions are evaluated:

```
Risk "PO Segregation":
  requires F_CREATE_PO AND F_APPROVE_PO

  F_CREATE_PO  BitSet: 1 1 0 1 0 0 1 ...
  F_APPROVE_PO BitSet: 0 1 1 1 0 1 0 ...
  AND result:          0 1 0 1 0 0 0 ...
                         ↑   ↑
                    violators
```

Checking 64 users takes one CPU instruction (`long & long`). 272 risks × 91,000 users = under 1 millisecond.

---

## 4. NonSAP Evaluation

NonSAP functions define their conditions as a **boolean expression over entitlements**:

```
F_CREATE_PO condition:
  (has entitlement E_PO_CREATE) OR (has entitlement E_PO_FULL_ACCESS)
```

This expression is compiled once at load time into a tree:

```java
// sealed interface NonSAPCondition
Or(
  HasEntitlement(E_PO_CREATE),
  HasEntitlement(E_PO_FULL_ACCESS)
)
```

**Evaluation for a user:**

```
evaluate(user.resolvedEntitlements):
  Or → try left branch first
    HasEntitlement(E_PO_CREATE):
      binarySearch(resolvedEntitlements, E_PO_CREATE_key)
      → found? → true → short-circuit, done ✅
```

The binary search is O(log n) on the sorted `resolvedEntitlements` array. No DB call. No object allocation.

**Complex case — nested AND/OR:**

```
F_APPROVE_PO condition:
  (has E_PO_APPROVE) AND ((has E_MANAGER) OR (has E_CONTROLLER))
```

Compiled tree:
```
And(
  HasEntitlement(E_PO_APPROVE),
  Or(
    HasEntitlement(E_MANAGER),
    HasEntitlement(E_CONTROLLER)
  )
)
```

Evaluation: And requires BOTH branches to be true. Left passes, then right is evaluated (Or — first match wins). The tree is walked depth-first, short-circuiting as early as possible.

**Operator precedence:** `&&` binds tighter than `||` (same as Java/math). The compiler respects this when building the tree from `function_entitlements` rows.

---

## 5. SAP Evaluation

SAP functions are more complex. Instead of a boolean expression over entitlement keys, a SAP function specifies:

> "The user must own a specific TCode AND have the required authorization object/field/value for that TCode."

### 5.1 The SAPFunctionDef structure

```
SAPFunctionDef for F_APPROVE_PO:
  Group 1 (endpoint: any):
    TCode ME28:
      Condition 1: auth object M_BEST_BSA, field ACTVT, value '02'-'02'
      Condition 2: auth object M_BEST_WRK, field WERKS, value '1000'-'9999'
    TCode ME29N:
      Condition 1: auth object M_BEST_BSA, field ACTVT, value '02'-'02'
      Condition 2: auth object M_BEST_WRK, field WERKS, value '1000'-'9999'
```

The semantics are:
```
OR across TCode groups
  └── OR across TCodes within a group
        └── AND across auth conditions per TCode
              └── OR across value ranges per condition
```

### 5.2 userAuthIndex — per-user auth lookup

Before evaluating any SAP function for a user, the engine builds an index from their resolved entitlements:

```
compositeKey = objectKey * 100000 + fieldKey

userAuthIndex: Map<Long compositeKey, List<AuthEntry>>

Example for user "arin":
  M_BEST_BSA/ACTVT → [
    AuthEntry(roleKey=1001, ACTVT, minValue='01', maxValue='01'),  ← from Z_MM_CREATOR
    AuthEntry(roleKey=1002, ACTVT, minValue='02', maxValue='02'),  ← from Z_MM_APPROVER
  ]
  M_BEST_WRK/WERKS → [
    AuthEntry(roleKey=1001, WERKS, minValue='1000', maxValue='1999'),
    AuthEntry(roleKey=1002, WERKS, minValue='1000', maxValue='9999'),
  ]
```

Two roles can both grant the same auth object/field with different value ranges. Both entries land in the same bucket. The evaluation tries each one — any match is enough.

### 5.3 The evaluation walk-through

**Evaluate F_APPROVE_PO for user "arin":**

```
resolvedEntitlements: [1001, 1002, 3001, 3002, 3003, 3004]
  (1001, 1002 = roles; 3001-3004 = tcodes ME21, ME22, ME28, ME29N)
```

**TCode ME28 (key=3003):**

Step 1 — Does arin own ME28?
```
starTcodeKeys.contains(3003)? No → must check ownership
binarySearch([1001,1002,3001,3002,3003,3004], 3003) → index 4, found ✅
```

Step 2 — Condition 1: `M_BEST_BSA/ACTVT` must overlap `'02'-'02'`
```
userAuthIndex.get(BSA*100000+ACTVT) → [entry('01','01'), entry('02','02')]

Try entry 1 (from Z_MM_CREATOR, '01'-'01'):
  rangesOverlapNumeric('02','02', '01','01')
  fMin=2, fMax=2, rMin=1, rMax=1
  (2≤1≤2)? No. (2≤1≤2)? No. (1≤2≤2)? No.  → false ❌

Try entry 2 (from Z_MM_APPROVER, '02'-'02'):
  rangesOverlapNumeric('02','02', '02','02')
  fMin=2, fMax=2, rMin=2, rMax=2
  (2≤2≤2)? Yes → true ✅

Condition 1 passed ✅
```

Step 3 — Condition 2: `M_BEST_WRK/WERKS` must overlap `'1000'-'9999'`
```
userAuthIndex.get(WRK*100000+WERKS) → [entry('1000','1999'), entry('1000','9999')]

Try entry 1 (from Z_MM_CREATOR, '1000'-'1999'):
  rangesOverlapNumeric('1000','9999', '1000','1999')
  fMin=1000, fMax=9999, rMin=1000, rMax=1999
  (1000≤1000≤9999)? Yes → true ✅

Condition 2 passed ✅
```

All conditions for ME28 passed → **ME28 satisfies the function**.
ME29N is never checked (OR short-circuits).

**Result:** bit set for arin in `F_APPROVE_PO`'s BitSet.

### 5.4 The three OR/AND rules summarised

| Level | Logic | Example |
|-------|-------|---------|
| Across TCodes | **OR** | ME28 passes → done, ME29N skipped |
| Across conditions per TCode | **AND** | ACTVT must pass **and** WERKS must pass |
| Across AuthEntries per condition | **OR** | Z_MM_CREATOR entry fails, Z_MM_APPROVER entry passes → condition passes |

### 5.5 Star TCode

When a function includes the wildcard TCode `*`, the user is considered to "own" every TCode — the binary search is skipped entirely. Only the auth object conditions are checked. This replicates ECMv4's star-tcode semantics exactly.

### 5.6 ValueMatcher — how ranges are compared

`rangesOverlapNumeric(funcMin, funcMax, roleMin, roleMax)`:

1. If all four values are purely numeric → parse as `long`, check numeric overlap:
   ```
   (fMin≤rMin≤fMax) OR (fMin≤rMax≤fMax) OR (rMin≤fMin≤rMax) OR (rMin≤fMax≤rMax)
   ```
2. Otherwise → fall back to string lexicographic range overlap (same four checks, string comparison).

Special cases:
- Either side is `*` → wildcard, always matches.
- Values in quotes (e.g., `"02"`) → treated as absolute (leading zeros preserved).
- `considerPrecedingZeros=false` → strip leading zeros before numeric parse.

---

## 6. SAPGROUP Evaluation

SAPGROUP is SAP evaluation with one additional constraint: **conflicting access must exist within the same SAP system instance (endpoint).**

### 6.1 Why SAPGROUP exists

A user might have:
- Account A on endpoint SAP-US → role `Z_MM_CREATOR` (can Create POs)
- Account B on endpoint SAP-EU → role `Z_MM_APPROVER` (can Approve POs)

Plain SAP merges all accounts → sees both roles → flags a violation.

SAPGROUP says: Create is in SAP-US, Approve is in SAP-EU. These are separate systems. No real-world conflict → **not a violation.**

If both roles were in SAP-US → same endpoint → **violation.**

### 6.2 The two structural changes

**Change 1 — userAuthIndex and tcode ownership are scoped per endpoint.**

Instead of building one index from all accounts, for each endpoint evaluation:
- Only auth entries from accounts belonging to that endpoint are included.
- The `resolvedEntitlements` used for TCode binary search contains only tcodes from that endpoint's accounts.

**Change 2 — one BitSet per endpoint instead of one global BitSet.**

```
Plain SAP:
  funcKey → BitSet

SAPGROUP:
  funcKey → Map<endpointKey, BitSet>
```

Bit `i` is set in `BitSet[endpointKey=SAP-US]` only if user `i` satisfies the function using SAP-US entitlements alone.

### 6.3 Violation detection for SAPGROUP

For a risk requiring F1 AND F2:

```
Plain SAP:
  violators = F1_bitset AND F2_bitset

SAPGROUP:
  violators = (F1[SAP-US] AND F2[SAP-US])
           OR (F1[SAP-EU] AND F2[SAP-EU])
           OR (F1[SAP-ASIA] AND F2[SAP-ASIA])
           ...
```

A user is a violator only if they satisfy both functions **within the same endpoint**.

### 6.4 What stays the same

The TCode ownership check, auth condition AND, AuthEntry OR, ValueMatcher — all identical to plain SAP. SAPGROUP only changes *what data is fed in* and *which BitSet the result is written to*.

---

## 7. Evidence Collection

Evidence answers: *"For this violation — which specific TCode and Role combination caused it?"*

Evidence is collected **during Phase 2** (not Phase 4), at zero extra cost:

When a TCode passes for a user during SAP evaluation:
1. Walk the `reverseGraph` (child→parent edges) to find the ancestor role that has the TCode as a direct assignment.
2. Walk further up to find the `directRole` (the role directly assigned to the user's account).
3. Store: `(accountKey, tcodeKey, associatedSapRole, directRole, endpointKey)`

This produces the detail rows written to `sodrisks_detail` — the audit trail showing exactly which roles and tcodes caused each violation.

---

## 8. Performance Summary

| Metric | Old System (ECMv4) | New System |
|--------|-------------------|------------|
| DB queries during evaluation | Thousands (per user per function) | ~10 total |
| Role hierarchy resolution | Re-queried per user | BFS once, cached |
| Function evaluation | Sequential | Parallel (virtual threads) |
| Violation detection | User-by-user comparison | BitSet AND (64 users/instruction) |
| Detail row write | One row at a time | Bulk LOAD DATA INFILE |
| Runtime (Hitachi, 49K accounts) | 21 hours | 1 min 47 sec |
| Memory | 64 GB | 1 GB |
| Detection accuracy | Baseline | 100% identical violations |

---

## 9. What Has Not Changed

- **The data model**: Risks, Functions, Entitlements, Roles, Accounts, Users — all read from the same tables.
- **The business logic**: Same conditions, same OR/AND semantics, same value matching rules.
- **The output**: Same violation rows, same detail rows (99.28% match; 0.72% gap is in audit trail only, zero detection difference).

The engine underneath was rebuilt entirely for speed. Nothing visible to the business changed.
