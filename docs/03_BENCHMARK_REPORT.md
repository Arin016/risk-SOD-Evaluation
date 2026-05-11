# SOD Evaluation Microservice — Benchmark Report

## Test Environments

**Hardware:** MacBook Pro M-series, 24 GB RAM
**Database:** MySQL 8.0 (local Docker)
**Old System:** ECMv4 Grails app running locally (768 MB - 12 GB heap)
**New System:** Spring Boot 3.5, Java 21 (512 MB - 1 GB heap)

---

## Benchmark 1: Test Data (Small Config)

**Data:** 18,300 accounts, 55 functions, 42 risks, 557 graph edges

| Metric | Old System | New System | Improvement |
|--------|-----------|------------|-------------|
| Total time | 872 sec (14.5 min) | **12 sec** | **73x faster** |
| Violations | 11,700 | 11,700 | Exact match |
| Detail rows | 25,800 | 25,800 | **Exact match + checksum** |
| Memory | 768 MB | 124 MB | **6x less** |
| Correctness | — | FP=0, FN=0 | ✅ |

---

## Benchmark 2: Real Production Data (System 5)

**Data:** 309 users, 108 functions, 205 risks, 2,628 graph parent nodes, 3,504 roles with auth

| Metric | Old System | New System | Improvement |
|--------|-----------|------------|-------------|
| Total time | 334 sec (5.5 min) | **24 sec** | **14x faster** |
| Detection | 14,806 | 14,806 | **Exact match** |
| Detail rows | 485,353 | 485,316 | 99.99% match |
| Exact row match | — | 481,843 / 485,353 | 99.28% |
| Memory | 595 MB (768 MB max) | 500 MB (512 MB max) | Less |
| Correctness | — | FP=0, FN=0 | ✅ |

---

## Benchmark 3: Hitachi Production Scale

**Data:** 91,500 accounts, 145 functions, 272 risks, 500,307 graph edges, 505,405 entitlement values

| Metric | Old System | New System | Improvement |
|--------|-----------|------------|-------------|
| Total time | **OOM at 768 MB** / 21 hrs on prod (64 GB) | **1 min 47 sec** | **~700x faster** |
| Violations | — (crashed) | 58,500 | ✅ |
| Detail rows | — | 129,000 | ✅ |
| Memory | 64 GB (production) | **1 GB** | **64x less** |
| Phase 1 (load + resolve) | — | 32 sec | — |
| Phase 2 (evaluate) | — | 27 sec | — |
| Phase 3 (detect) | — | <1 sec | — |
| Phase 4 (write) | — | 36 sec | — |

---

## Phase Breakdown (System 5)

| Phase | Time | What it does |
|-------|------|-------------|
| Phase 0: Config | 0.2 sec | Load risks, functions, conditions, star tcodes |
| Phase 1: Graph + Resolve | 4 sec | Load entitlements2, resolve BFS per user |
| Phase 2: Evaluate | 1.7 sec | 108 functions in parallel → BitSets + evidence |
| Phase 3: Detect | <1 ms | BitSet AND across 205 risks |
| Phase 4: Write | 14 sec | Summary rows (batch INSERT) + detail rows (LOAD DATA) |
| Validation | 0.6 sec | Compare against old system |
| **Total** | **24 sec** | — |

---

## Correctness Verification Method

1. Run old system (ECMv4 job) on same data
2. Run new system on same data
3. Compare:
   - `sodrisks` row count (violations)
   - User+Risk+Endpoint pair matching (FP/FN analysis)
   - `sodrisk_entitlement` row count (detail rows)
   - CRC32 checksum on (FUNCTIONKEY, TCODEKEY, ASSOCIATEDSAPROLEKEY) tuples
4. Automated in every run via `ValidationService` + checksum query

---

## Cost Projection (300 Tenants)

| Tier | Tenants | Old Infra | New Infra | Savings |
|------|---------|-----------|-----------|---------|
| Small (<5K accounts) | 200 | r5.xlarge (32 GB) | t3.medium (4 GB) | ~$154/mo each |
| Medium (5-20K) | 80 | r5.2xlarge (64 GB) | t3.large (8 GB) | ~$300/mo each |
| Large (20-50K) | 15 | r5.4xlarge (128 GB) | t3.xlarge (16 GB) | ~$600/mo each |
| XL (50K+) | 5 | r5.8xlarge (256 GB) | r5.large (16 GB) | ~$1,400/mo each |
| **Total** | **300** | **~$84K/mo** | **~$9K/mo** | **~$75K/mo saved** |

**Annual savings: ~$900K**
