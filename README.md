# SOD Evaluation Microservice

A high-performance Segregation of Duties (SOD) evaluation engine built from scratch to replace the legacy ECMv4 Grails-based `RiskSODEvaluationJob`. Produces identical violation detection results while being 700x faster and using 64x less memory.

## What This Does

SOD evaluation determines which users in an organization have conflicting access rights (e.g., a user who can both create and approve purchase orders). This system:

1. Loads the role hierarchy graph and resolves each user's effective entitlements
2. Evaluates SAP and NonSAP functions against all users in parallel
3. Detects violations by intersecting function satisfaction across risk definitions
4. Writes violation records and evidence detail rows to the database

## Key Results

| Scale | Old System | This System |
|-------|-----------|-------------|
| 91,500 accounts, 500K graph edges | OOM / 21 hours (64 GB) | 1 min 47 sec (1 GB) |
| 309 users, 108 functions (real SAP) | 334 sec | 24 sec |
| 18,300 accounts (test data) | 872 sec | 12 sec |

Detection accuracy: 100% match with the old system (zero false positives, zero false negatives).

## Repository Structure

```
src/main/java/com/saviynt/sod/evaluation/
  controller/        API endpoint
  dao/               Data access (raw JDBC, no ORM)
  dto/               Request/response objects
  model/             Domain records (Risk, Function, UserAccess, etc.)
  service/           Core evaluation logic
    EvaluationOrchestrator.java   Pipeline coordinator (5 phases)
    AccessGraphService.java       BFS graph resolution + reverse graph
    FunctionEvaluationService.java SAP/NonSAP evaluation + evidence
    ViolationDetectionService.java BitSet AND across risk functions
    ValueMatcher.java             Auth value range matching
    ValidationService.java        Comparison against old system

src/test/java/com/saviynt/sod/testdata/
  TestDataGenerator.java          Configurable test data (small/hitachi profiles)
  ScenarioBuilder.java            24 test scenarios

docs/
  01_EXECUTIVE_SUMMARY.md         Business impact, cost savings
  02_TECHNICAL_ARCHITECTURE.md    System design, data flow, API
  03_BENCHMARK_REPORT.md          Performance numbers, phase breakdown
  04_CODE_WALKTHROUGH.md          File-by-file explanation
  05_ALGORITHM_DEEP_DIVE.md       Phase-by-phase pseudocode + complexity
  05b_ALGORITHM_OVERVIEW.md       Simplified explanation for stakeholders
  06_TEST_SCENARIOS.md            All 24 scenarios documented
  07_KNOWN_LIMITATIONS.md         Gaps, differences, what's pending
  08_LLM_HANDOFF_CONTEXT.md       Full context for AI-assisted development
```

## How to Run

Prerequisites: Java 21, MySQL 8 with a Saviynt ECM database (ecmg6new).

```bash
# Start the service
./mvnw spring-boot:run -Dspring-boot.run.jvmArguments="-Xmx1g"

# Trigger evaluation (test data, system 200)
curl -X POST http://localhost:9220/sod-eval/api/v1/evaluate/sync \
  -H "Content-Type: application/json" \
  -d '{"rulesetKeys": [200], "securitySystemId": 200}'

# Trigger evaluation (real SAP data, system 5)
curl -X POST http://localhost:9220/sod-eval/api/v1/evaluate/sync \
  -H "Content-Type: application/json" \
  -d '{"rulesetKeys": [1], "securitySystemId": 5}'
```

## Test Data Generation

```bash
# Small config (18K accounts, for correctness verification with old system)
java -cp "target/test-classes:mysql-connector.jar" \
  com.saviynt.sod.testdata.TestDataGenerator small

# Hitachi-scale (91K accounts, stress test)
java -cp "target/test-classes:mysql-connector.jar" \
  com.saviynt.sod.testdata.TestDataGenerator hitachi
```

## Architecture

The evaluation runs in 5 phases:

- **Phase 0** — Load configuration (risks, functions, auth conditions)
- **Phase 1** — Load role hierarchy graph, resolve user entitlements via BFS
- **Phase 2** — Evaluate all functions in parallel (virtual threads), collect evidence
- **Phase 3** — Detect violations via BitSet AND across risk functions
- **Phase 4** — Write results to DB (LOAD DATA INFILE for bulk performance)

See `docs/02_TECHNICAL_ARCHITECTURE.md` for the full design.

## Documentation Guide

- Starting from scratch? Read `docs/01_EXECUTIVE_SUMMARY.md` then `docs/02_TECHNICAL_ARCHITECTURE.md`
- Reviewing the code? Read `docs/04_CODE_WALKTHROUGH.md`
- Understanding the algorithm? Read `docs/05_ALGORITHM_DEEP_DIVE.md`
- Running tests? Read `docs/06_TEST_SCENARIOS.md`
- Continuing development (AI-assisted)? Read `docs/08_LLM_HANDOFF_CONTEXT.md`

## Technology

- Java 21 (virtual threads, records, sealed interfaces)
- Spring Boot 3.5
- MySQL 8 (LOAD DATA INFILE for bulk writes)
- Raw JDBC (no ORM overhead)
