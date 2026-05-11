# SOD Evaluation — How the New Engine Works



---

## The 30-Second Version

The new SOD engine takes the **exact same data** (Risks, Functions, TCodes, Roles, Accounts, Users) and produces the **exact same violations**. Nothing about the business logic changes. What changes is *how* the computation is performed — like replacing a hand calculator with a supercomputer.

**Result:** 700× faster, 64× less memory, 100% identical output.

---

## What Goes In, What Comes Out

```
┌─────────────────────────────────────────────────────────────┐
│                         INPUT                               │
│                                                             │
│  • Risks (which function combinations are violations)       │
│  • Functions (what entitlements define each function)        │
│  • Entitlements (TCodes, Roles, Auth Objects)               │
│  • Users & Accounts (who has access to what)                │
│  • Role Hierarchy (which roles contain which sub-roles)     │
└─────────────────────┬───────────────────────────────────────┘
                      │
                      ▼
┌─────────────────────────────────────────────────────────────┐
│                    COMPUTATION                              │
│                                                             │
│  "For each user, which functions do they satisfy?           │
│   For each risk, do they satisfy ALL required functions?"   │
│                                                             │
└─────────────────────┬───────────────────────────────────────┘
                      │
                      ▼
┌─────────────────────────────────────────────────────────────┐
│                         OUTPUT                              │
│                                                             │
│  • Violations (User X violates Risk Y)                     │
│  • Evidence (which specific roles/tcodes caused it)         │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

The input is the same. The output is the same. Only the middle box changed.

---

## Old vs. New: The Library Analogy

Imagine you need to check whether 91,000 employees each have a dangerous combination of access.

**Old system (ECMv4):**
Like a librarian who walks to the shelf, pulls one book, reads one page, walks back, writes a note, then walks to the shelf again — 91,000 times per function. For each employee, it asks the database hundreds of individual questions, one at a time.

**New system:**
Like photocopying the entire catalog once, spreading it across a table, and checking everyone simultaneously. It loads all the data into memory upfront, then computes everything in one pass without going back to the database.

---

## The Four Steps (Plain English)

### Step 1 — Load Everything Once

Pull all Risks, Functions, Role Hierarchies, and User-Account assignments into memory in ~10 database queries. The old system made thousands of queries throughout its run.

### Step 2 — Resolve "Who Has What"

For each user, trace through the role hierarchy to determine their complete set of entitlements. The old system did this with massive SQL self-joins (imagine a 14-table JOIN). The new system walks the hierarchy in memory and **remembers** results — if 5,000 users share the same role, it only traces that role once.

### Step 3 — Evaluate All Functions (in Parallel)

For each function, mark which users satisfy it. Instead of storing a full record per user, the system uses a single **bit** (0 or 1) per user. 91,000 users = ~11 kilobytes per function. The old system stored full data objects per user — megabytes per function.

All functions are evaluated simultaneously using parallel threads.

### Step 4 — Detect Violations (Instant)

A risk says: "User must satisfy Function A **AND** Function B." The system simply overlaps the two bit-lists. Where both bits are 1 → violation. This operation checks 64 users in a single CPU instruction.

For 272 risks × 91,000 users: **less than 1 millisecond.**

---

## Why It's Faster — Key Differences

| | Old System | New System |
|---|---|---|
| **Database calls** | Thousands (per user, per function) | ~10 total (load once) |
| **Role hierarchy** | Re-queries for every user | Traces once, caches result |
| **Function evaluation** | One at a time, sequentially | All at once, in parallel |
| **Violation detection** | Compare user-by-user | Bulk compare (64 users per operation) |
| **Writing results** | One row at a time | Bulk file load (485K rows in 1.3 sec) |

---

## Key Numbers

| Metric | Old System | New System |
|--------|-----------|------------|
| **Runtime (Hitachi, 49K accounts)** | 21 hours | 1 min 47 sec |
| **Speed improvement** | — | **700×** |
| **Memory** | 64 GB | 1 GB |
| **Memory improvement** | — | **64×** |
| **Accuracy** | Baseline | **100% identical violations** |

---

## What This Means for the Business

- **Drop-in replacement:** Same API, same inputs, same outputs. No changes needed upstream or downstream.
- **Same data model:** Risks, Functions, Entitlements, Users, Accounts — all unchanged.
- **Same results:** Every violation the old system finds, the new system finds. No more, no less.
- **Estimated savings:** ~$900K/year in infrastructure costs across 300 tenants.
- **Unlocks scale:** Customers that currently crash or timeout (like Hitachi at 21 hours) now complete in under 2 minutes.

The business logic is preserved exactly. Only the computation engine underneath was rebuilt from scratch for speed.
