# Agent SOD — Implementation Handoff for Niranjan

## Status: Working End-to-End (2026-05-19)

The agent SOD evaluation is **fully functional** using the existing SOD evaluation engine with zero engine modifications. Agent data is mapped into the same tables the engine reads from (`entitlement_values`, `entitlements2`, `entitlement_objects`, `account_entitlements1`). Rules are defined in the same tables (`functions`, `function_objects`, `function_entitlements`, `risks`). Violations land in `sodrisks_new_job`.

### Test Results

```
8 functions evaluated, 5 risks evaluated, 3 users (2 agents + 1 cross-agent group)
11 violations detected in 359ms

Violations:
  AGENT::shadow-data-exfil-bot     | Data Exfiltration Path
  AGENT::shadow-data-exfil-bot     | Chain Agent with Anonymous Cred
  AGENT::shadow-data-exfil-bot     | Autonomous Agent with External Data
  AGENT::shadow-data-exfil-bot     | No-Auth Connector + Internal Data (SAP)
  AGENT::shadow-data-exfil-bot     | Internal Data + Chain (Mixed SAP+NonSAP)
  AGENT::AccessReviewAgent         | Autonomous Agent with External Data
  CROSS_AGENT::shared_cred_4401c99c| All 5 risks (union of both agents)
```

---

## How to Run

### Prerequisites
- Java 21
- MySQL 8 with `ecmg6new` database (existing Saviynt ECM schema)
- The SOD evaluation service repo at `/Users/arin.mallanna/AAG/sod-evaluation-service`

### Start the Service

```bash
cd /Users/arin.mallanna/AAG/sod-evaluation-service
./mvnw package -q -DskipTests
java -Xmx1g -jar target/sod-evaluation-service-1.0-SNAPSHOT.jar
# Runs on port 9220, context path /sod-eval
```

### API Base URL

```
http://localhost:9220/sod-eval/api/v1/agent-sod
```

---

## API Reference

### 1. Import Agent Data

```bash
POST /api/v1/agent-sod/import
Content-Type: application/json

{
  "agents": [ <agent-details JSON>, <agent-details JSON>, ... ],
  "credentials": <all-agents-credentials JSON>
}
```

**What it does:**
- Parses each agent's components, knowledge sources, credentials, chain edges
- Creates entitlement_values for each agent, tool, KS, credential, and flag
- Creates entitlements2 edges (agent → children, agent → sub-agent for chains)
- Creates entitlement_objects (attributes on each capability)
- Creates accounts + users for AGENT_ONLY evaluation
- Creates cross-agent virtual accounts for shared credentials
- Attempts OWNER_COMPOSITE linking (if owner exists in ECM users table)

**Idempotent:** Wipes all system-300 data before re-importing. Safe to call repeatedly.

**Example:**
```bash
# Build payload from sample files
python3 -c "
import json
with open('Downloads/handoff/samples/shadow-data-exfil-bot/03-agent-details.json') as f: a1 = json.load(f)
with open('Downloads/handoff/samples/accessreviewagent/03-agent-details.json') as f: a2 = json.load(f)
with open('Downloads/handoff/samples/_shared/all-agents-credentials.json') as f: creds = json.load(f)
print(json.dumps({'agents': [a1, a2], 'credentials': creds}))
" > /tmp/payload.json

curl -X POST http://localhost:9220/sod-eval/api/v1/agent-sod/import \
  -H "Content-Type: application/json" -d @/tmp/payload.json
# Response: {"agentsImported":2,"crossAgentGroups":1}
```

---

### 2. Create Ruleset

```bash
POST /api/v1/agent-sod/rulesets
{"name": "Agent SOD Rules", "description": "SOD rules for AI agents"}
# Response: {"rulesetKey": 203}
```

---

### 3. Create Functions

Two types: **SAP** (attribute matching on tools/KS/creds) and **NONSAP** (flag presence check).

#### NonSAP Function (boolean flag check)

```bash
POST /api/v1/agent-sod/functions
{"name": "Has Chain Edges", "type": "NONSAP", "rulesetKey": 203}
# Response: {"functionKey": 654}

# Add condition: check if flag entitlement is in resolved set
POST /api/v1/agent-sod/functions/654/conditions
{"entitlementKey": 300400001, "position": 1}
```

For OR conditions (multiple flags):
```bash
POST /api/v1/agent-sod/functions/653/conditions
{"entitlementKey": 300400010, "position": 1, "nextOperator": "||"}

POST /api/v1/agent-sod/functions/653/conditions
{"entitlementKey": 300400007, "position": 2}
# This means: HAS_EXTERNAL_KS OR HAS_ANONYMOUS_CRED
```

#### SAP Function (attribute matching)

```bash
POST /api/v1/agent-sod/functions
{"name": "Has No-Auth Connector", "type": "SAP", "rulesetKey": 203}
# Response: {"functionKey": 658}

# Add condition: check entitlement_objects for AUTH_SCHEME = "none"
POST /api/v1/agent-sod/functions/658/conditions
{
  "groupKey": 1,
  "tcodeKey": 300000000,
  "objectKey": 9003,
  "fieldKey": 1,
  "minValue": "none",
  "maxValue": "none"
}
```

For AND conditions (same groupKey):
```bash
# Both must match (same group = AND):
POST /api/v1/agent-sod/functions/659/conditions
{"groupKey": 1, "tcodeKey": 300000000, "objectKey": 9002, "fieldKey": 1, "minValue": "Maker", "maxValue": "Maker"}

POST /api/v1/agent-sod/functions/659/conditions
{"groupKey": 1, "tcodeKey": 300000000, "objectKey": 9001, "fieldKey": 1, "minValue": "Dynamics", "maxValue": "Dynamics~"}
# This means: CREDENTIAL_MODE = "Maker" AND CONNECTOR_NAME starts with "Dynamics"
```

For OR conditions (different groupKey):
```bash
# Either group satisfies (different groups = OR):
POST /api/v1/agent-sod/functions/660/conditions
{"groupKey": 1, "tcodeKey": 300000000, "objectKey": 9004, "fieldKey": 1, "minValue": "SharePoint", "maxValue": "SharePoint"}

POST /api/v1/agent-sod/functions/660/conditions
{"groupKey": 2, "tcodeKey": 300000000, "objectKey": 9004, "fieldKey": 1, "minValue": "Dataverse", "maxValue": "Dataverse"}
# This means: KS_KIND = "SharePoint" OR KS_KIND = "Dataverse"
```

---

### 4. Create Risks

```bash
POST /api/v1/agent-sod/risks
{
  "name": "Data Exfiltration Path",
  "rulesetKey": 203,
  "functionKeys": [652, 653]
}
# Response: {"riskId": 777}
# Violation fires when BOTH functions are satisfied by the same user/agent
```

---

### 5. Evaluate

```bash
POST /api/v1/agent-sod/evaluate
{"rulesetKey": 203}
# Response:
# {
#   "status": "SUCCESS",
#   "totalAccounts": 3,
#   "totalUsers": 3,
#   "functionsEvaluated": 8,
#   "risksEvaluated": 5,
#   "violationsOpened": 11,
#   "duration": "PT0.359245S"
# }
```

---

### 6. Get Violations

```bash
GET /api/v1/agent-sod/violations?rulesetKey=203
# Response:
# [
#   {"SODKEY":1464419, "USERIDENTIFIER":9000003, "RISKKEY":777,
#    "RISKNAME":"Data Exfiltration Path", "STATUS":1,
#    "USERNAME":"CROSS_AGENT::shared_cred_4401c99c"},
#   ...
# ]
```

---

## Data Model Summary

### SecuritySystem / Endpoint / Types

| Entity | Key | Name |
|--------|-----|------|
| Security System | 300 | AgentSystem |
| Endpoint | 300 | AgentEndpoint |
| Entitlement Type | 600 | Agent |
| Entitlement Type | 601 | AgentCapability |

### Key Ranges

| Entity | Range | Example |
|--------|-------|---------|
| Star tcode | 300000000 | `*` (always in resolved set) |
| Agents | 300000001–300000999 | `shadow-data-exfil-bot` |
| Tools | 300100001–300199999 | `shadow-data-exfil-bot::TOOL::mcpplayground` |
| Knowledge Sources | 300200001–300299999 | `shadow-data-exfil-bot::KS::SharePoint` |
| Credentials | 300300001–300399999 | `shadow-data-exfil-bot::CRED::mcpplayground` |
| Flags | 300400001–300400099 | `FLAG::HAS_CHAIN_EDGES` |
| Users | 9000001–9000999 | `AGENT::shadow-data-exfil-bot` |
| Accounts | 3000001–3000999 | `AGENT::shadow-data-exfil-bot::acc` |

### Object/Field Keys (for entitlement_objects)

| objectKey | fieldKey | Meaning | Example Values |
|-----------|----------|---------|----------------|
| 9001 | 1 | CONNECTOR_NAME | "Jira", "Dynamics 365", "mcpplayground" |
| 9002 | 1 | CREDENTIAL_MODE | "Maker", "Invoker" |
| 9003 | 1 | AUTH_SCHEME | "oauth2", "apiKey", "none" |
| 9004 | 1 | KS_KIND | "SharePoint", "PublicSite", "BingCustomSearch", "Dataverse" |
| 9005 | 1 | KS_SITE | URL of the knowledge source |
| 9006 | 1 | CONNECTOR_ENDPOINT | URL of the connector target |
| 9007 | 1 | CONFIRMATION_MODE | "Strict", "None" |
| 9008 | 1 | ACTION_CATEGORY | "READ", "CREATE", "UPDATE", "DELETE", "APPROVE", "EXECUTE" |
| 9009 | 1 | CONNECTION_ID | UUID of the shared connection |
| 9010 | 1 | OWNER_ENTRA_ID | UUID of credential owner |
| 9011 | 1 | IS_MCP | "true" |
| 9012 | 1 | IS_CUSTOM | "true" |
| 9013 | 1 | MCP_TOOLS_MODE | "UseAllTools", "UseSelectedTools" |

### Flag Entitlements (for NonSAP conditions)

| Key | Flag | When Set |
|-----|------|----------|
| 300400001 | HAS_CHAIN_EDGES | Agent has AgentDialog components (invokes sub-agents) |
| 300400002 | MCP_USE_ALL_TOOLS | Any tool has mcpToolsMode = UseAllTools |
| 300400003 | GENERATIVE_ACTIONS | Agent has generativeActionsEnabled = true |
| 300400004 | AUTH_MODE_NONE | Agent authMode = 1 (no authentication required) |
| 300400005 | ACCESS_POLICY_MULTI_TENANT | Agent accessControlPolicy = 3 |
| 300400006 | ACCESS_POLICY_ANY | Agent accessControlPolicy = 0 |
| 300400007 | HAS_ANONYMOUS_CRED | Any credential has authScheme = "none" |
| 300400008 | HAS_MAKER_CRED | Any credential mode = "Maker" |
| 300400009 | CROSS_OWNER_CHAIN | Chain spans multiple owners |
| 300400010 | HAS_EXTERNAL_KS | Has PublicSite or BingCustomSearch knowledge source |
| 300400011 | HAS_INTERNAL_KS | Has SharePoint or Dataverse knowledge source |
| 300400012 | IDENTITY_HYBRID | Mix of Maker and Invoker tools |
| 300400013 | IDENTITY_STANDING | All tools use Maker (standing identity) |

---

## How Each Risk Type Works

### AGENT_ONLY

Each agent gets a fake user (`AGENT::shadow-data-exfil-bot`) + account. The account is assigned the agent entitlement. BFS resolves all tools/KS/creds/flags. Functions evaluate against the resolved set.

**Already working.** shadow-data-exfil-bot fires 5 violations.

### CHAIN

Agent → SubAgent edge in `entitlements2`. BFS from the parent naturally traverses into sub-agent's tools. The resolved set contains BOTH agents' capabilities.

**Already working.** shadow-data-exfil-bot has chain edges to AccessReviewAgent. The CROSS_AGENT group (which includes both via shared credential) demonstrates the union evaluation.

### CROSS_AGENT

Virtual user + account assigned to multiple agent entitlements. BFS resolves the union of all agents' tools.

**Already working.** `CROSS_AGENT::shared_cred_4401c99c` groups shadow-data-exfil-bot + AccessReviewAgent (they share the sportsdb credential connectionId).

### OWNER_COMPOSITE

Owner's existing ECM userKey gets an additional account in system 300 assigned to the agent entitlement. Engine merges owner's SAP entitlements + agent tools into one resolved set.

**Data model ready.** Not firing in test because sample agent owners don't exist in this ECM DB. Will work when:
1. Real owner users exist in the `users` table, OR
2. We create synthetic owner users with SAP entitlements for testing

### INVOKER_COMPOSITE

Same pattern as OWNER_COMPOSITE. Each invoker gets an account in system 300.

**Data model ready.** Needs invoker group expansion (Graph API) to populate.

---

## How to Add New Rules

### Example: "Maker credential on financial system + agent has chain edges"

```bash
# 1. Create function: Has Maker on Finance (SAP - AND within group)
curl -X POST "$BASE/functions" -H "Content-Type: application/json" \
  -d '{"name":"Maker on Finance","type":"SAP","rulesetKey":203}'
# Returns: {"functionKey": 660}

# 2. Add AND conditions (same groupKey = 1)
curl -X POST "$BASE/functions/660/conditions" -H "Content-Type: application/json" \
  -d '{"groupKey":1,"tcodeKey":300000000,"objectKey":9002,"fieldKey":1,"minValue":"Maker","maxValue":"Maker"}'

curl -X POST "$BASE/functions/660/conditions" -H "Content-Type: application/json" \
  -d '{"groupKey":1,"tcodeKey":300000000,"objectKey":9001,"fieldKey":1,"minValue":"Dynamics","maxValue":"Dynamics~"}'

# 3. Create risk pairing with existing "Has Chain Edges" function (654)
curl -X POST "$BASE/risks" -H "Content-Type: application/json" \
  -d '{"name":"Maker Finance + Chain Delegation","rulesetKey":203,"functionKeys":[660,654]}'

# 4. Evaluate
curl -X POST "$BASE/evaluate" -H "Content-Type: application/json" -d '{"rulesetKey":203}'
```

### Example: "Agent with no auth + multi-tenant access policy"

```bash
# Both are NonSAP flags — create a risk pairing them
curl -X POST "$BASE/risks" -H "Content-Type: application/json" \
  -d '{"name":"No Auth + Multi-Tenant","rulesetKey":203,"functionKeys":[<auth_mode_none_func_key>,<multi_tenant_func_key>]}'
```

---

## Range/Prefix Matching (SAP-style)

The `minValue`/`maxValue` in SAP conditions support range matching:

| Pattern | minValue | maxValue | Matches |
|---------|----------|----------|---------|
| Exact | "Maker" | "Maker" | Only "Maker" |
| Prefix | "Dynamics" | "Dynamics~" | "Dynamics 365", "Dynamics AX", etc. |
| Range | "A" | "M" | Any string from A to M |
| Wildcard | "" | "~" | Everything (any value) |

The `~` character sorts after all alphanumeric characters in string comparison.

---

## File Locations

```
sod-evaluation-service/
  src/main/java/com/saviynt/sod/evaluation/agent/
    AgentImportDao.java        — DB operations (insert/delete for system 300)
    AgentImportService.java    — JSON parsing → table mapping logic
    AgentRuleDao.java          — CRUD for rulesets, functions, conditions, risks
    AgentSodController.java    — REST API endpoints
  docs/
    AGENT_SOD_DATA_MODEL.md    — Full data model design doc
    AGENT_SOD_NIRANJAN_HANDOFF.md  — This file
```

---

## What Niranjan Needs to Do (ST Preventive/Detective)

1. **Start the service** (instructions above)
2. **Import agent data** — use the sample JSONs or fetch fresh from Copilot365 backend
3. **Define rules** — create functions + conditions + risks via API for the patterns you want to detect
4. **Evaluate** — hit the evaluate endpoint
5. **Check violations** — they appear in `sodrisks_new_job` table (same as human SOD violations)

For the **ST (single-tenant) preventive/detective** demo:
- The import API handles data loading
- The evaluate API triggers the existing SOD engine
- Violations are in the same output table the Saviynt UI reads
- No new infrastructure needed

---

## What's Next

| Item | Status | Owner |
|------|--------|-------|
| Import API | ✅ Done | Arin |
| Rule CRUD API | ✅ Done | Arin |
| Evaluate API | ✅ Done | Arin |
| AGENT_ONLY violations | ✅ Working | — |
| CROSS_AGENT violations | ✅ Working | — |
| CHAIN violations (via BFS) | ✅ Working | — |
| SAP attribute matching | ✅ Working | — |
| NonSAP flag matching | ✅ Working | — |
| Mixed SAP+NonSAP risks | ✅ Working | — |
| OWNER_COMPOSITE | 🔨 Data model ready, needs real owner users | Arin |
| INVOKER_COMPOSITE | 🔨 Data model ready, needs Graph API | Future |
| MT → ST data flow | 📋 TODO | Arin/Niranjan |
| More risk patterns from PRD | 📋 Define via API | Niranjan |
| Starter ruleset (auto-created on import) | 📋 TODO | Arin |
