# SOD Evaluation Microservice — Technical Architecture

## System Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                    SOD Evaluation Microservice                    │
│                    (Java 21, Spring Boot 3.5)                     │
├─────────────────────────────────────────────────────────────────┤
│                                                                   │
│  Phase 0: Load Config                                            │
│  ├── Risks, Functions, Conditions                                │
│  ├── Star tcode keys, Excluded ent pairs                         │
│  ├── considerPrecedingZeros config                               │
│  └── TCD-field resolved tcodes                                   │
│                                                                   │
│  Phase 1: Load Graph + Resolve Access                            │
│  ├── ENTITLEMENTS2 → adjacency list (parent→children)            │
│  ├── Reverse graph (child→parents)                               │
│  ├── ACCOUNT_ENTITLEMENTS1 → direct assignments                  │
│  ├── ENTITLEMENT_OBJECTS → auth entries per role                 │
│  └── BFS per account → resolved entitlements (cached)            │
│                                                                   │
│  Phase 2: Evaluate Functions (parallel, virtual threads)         │
│  ├── SAP: tcode ownership + auth matching → BitSet               │
│  ├── NonSAP: boolean condition evaluation → BitSet               │
│  └── Evidence collection (tcode, role, directRole per user)      │
│                                                                   │
│  Phase 3: Detect Violations                                      │
│  └── BitSet AND across risk functions → violator BitSets         │
│                                                                   │
│  Phase 4: Persist                                                │
│  ├── Write sodrisks_new_job (summary rows)                       │
│  ├── Write detail rows to temp CSV file (100-150ms)              │
│  └── LOAD DATA INFILE into sodrisk_entitlement_new_job (~1 sec)  │
│                                                                   │
│  Phase 5: Validation + Cleanup                                   │
│  └── Compare against old system's sodrisks table                 │
│                                                                   │
└─────────────────────────────────────────────────────────────────┘
```

## Data Flow

```
MySQL DB
  │
  ├── entitlements2 ──────────────→ Graph (parent→children[]) + Reverse Graph (child→parents[])
  ├── account_entitlements1 ──────→ Direct assignments per account
  ├── entitlement_objects ────────→ Auth entries per role (obj, field, min, max)
  ├── function_objects ───────────→ SAP function conditions (tcode, obj, field, value ranges)
  ├── function_entitlements ──────→ NonSAP boolean conditions (AND/OR tree)
  ├── risks ──────────────────────→ Risk definitions (function1-5 keys)
  │
  ▼
  BFS Resolution: account → direct roles → resolved entitlements (sorted long[])
  │
  ▼
  Function Evaluation (parallel):
  │  For each user: does their resolved ents + auth satisfy the function?
  │  Result: BitSet per function (bit i = user i satisfies)
  │
  ▼
  Violation Detection:
  │  For each risk: AND the BitSets of its functions
  │  Result: BitSet per risk (bit i = user i violates)
  │
  ▼
  Persist: Write violations + detail rows to DB
```

## Key Design Decisions

### 1. BFS with Memoization (vs old system's depth-N SQL joins)
- Old system: runs a separate SQL query per depth level (up to 14 self-joins)
- New system: loads graph once, BFS per account with `resolvedCache` (same role resolved once, reused across accounts)
- Result: O(edges) load + O(accounts × avg_reachable) resolve vs O(accounts × depth × DB_roundtrip)

### 2. BitSet per Function (vs old system's HashMap per account)
- Old system: `accountEntInFunctionMap` = Map<userKey, Set<entitlementData>> — huge memory
- New system: one BitSet per function, bit i = user i satisfies — O(users/64) per AND operation
- Result: violation detection is microseconds (BitSet AND) vs seconds (HashMap intersection)

### 3. Evidence During Evaluation (vs separate Phase 4 computation)
- Old system: writes detail rows during evaluation (interleaved with computation)
- New system: collects evidence during Phase 2 (zero extra cost), writes in Phase 4 (pure I/O)
- Result: Phase 4 is 1.4 sec (file write + LOAD DATA) vs old system's minutes of per-row INSERTs

### 4. Parallel Virtual Threads (vs old system's sequential)
- Old system: evaluates functions one by one, shares mutable state between them
- New system: evaluates all functions in parallel using Java 21 virtual threads
- Result: 108 functions in 1.7 sec (parallel) vs ~200 sec (sequential)

### 5. LOAD DATA INFILE (vs batch INSERT)
- Old system: individual Hibernate save() per violation row
- New system: writes all detail rows to temp CSV, then bulk loads via LOAD DATA INFILE
- Result: 485K rows in 1.3 sec vs minutes of individual INSERTs

## Memory Model

| Component | System 5 (309 users) | Hitachi (91.5K users) |
|-----------|---------------------|----------------------|
| Graph + Reverse | ~40 MB | ~40 MB |
| Auth entries | ~200 MB | ~200 MB |
| Resolved ents | ~10 MB | ~73 MB |
| UserAccess objects | ~1 MB | ~18 MB |
| BitSets | <1 MB | ~2 MB |
| Evidence | ~3 MB | ~5 MB |
| JVM overhead | ~200 MB | ~200 MB |
| **Total** | **~500 MB** | **~1 GB** |

## API

```
POST /sod-eval/api/v1/evaluate/sync
Content-Type: application/json

{
  "rulesetKeys": [200],
  "securitySystemId": 200,
  "accountQuery": null,
  "entitlementQuery": null
}

Response:
{
  "jobId": 1778498236562,
  "status": "SUCCESS",
  "totalAccounts": 91500,
  "totalUsers": 91500,
  "functionsEvaluated": 145,
  "risksEvaluated": 272,
  "violationsOpened": 58500,
  "duration": "PT1M46.990121S"
}
```

## Technology Stack
- Java 21 (virtual threads, records, sealed interfaces)
- Spring Boot 3.5
- MySQL 8 (LOAD DATA INFILE for bulk writes)
- No ORM — raw JDBC for maximum performance
