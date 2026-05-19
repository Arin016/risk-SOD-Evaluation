# Agent SOD — Data Model & Evaluation Design

## Overview

This document describes how AI agent data (from Microsoft Copilot Studio) is mapped into the existing SOD evaluation engine's data model. The engine runs **unmodified** — all agent SOD evaluation is achieved purely through data insertion into existing tables.

**Core principle:** An agent is an entitlement. Its tools, knowledge sources, and credentials are child entitlements. The engine's BFS graph traversal resolves the full capability surface, and existing SAP/NonSAP function evaluation detects violations.

---

## Key Space

All agent data lives under **securitySystemId=300**, isolated from human SOD (system 5, 200, etc).

```
Entitlement keys (entitlement_values.ENTITLEMENT_VALUEKEY):
  Star tcode:  300000000                      (wildcard — every agent "owns" this)
  Agents:      300000001 .. 300000999         (one per agent, max 999)
  Tools:       300100001 .. 300199999         (one per tool across all agents)
  KS:          300200001 .. 300299999         (one per knowledge source)
  Credentials: 300300001 .. 300399999         (one per credential binding)
  Flags:       300400001 .. 300400099         (structural boolean properties)

Object/field keys (for entitlement_objects):
  objectKey 9001, fieldKey 1 = CONNECTOR_NAME
  objectKey 9002, fieldKey 1 = CREDENTIAL_MODE
  objectKey 9003, fieldKey 1 = AUTH_SCHEME
  objectKey 9004, fieldKey 1 = KS_KIND
  objectKey 9005, fieldKey 1 = KS_SITE
  objectKey 9006, fieldKey 1 = CONNECTOR_ENDPOINT
  objectKey 9007, fieldKey 1 = CONFIRMATION_MODE
  objectKey 9008, fieldKey 1 = ACTION_CATEGORY
  objectKey 9009, fieldKey 1 = CONNECTION_ID
  objectKey 9010, fieldKey 1 = OWNER_ENTRA_ID
  objectKey 9011, fieldKey 1 = IS_MCP
  objectKey 9012, fieldKey 1 = IS_CUSTOM
  objectKey 9013, fieldKey 1 = MCP_TOOLS_MODE

Synthetic flag keys:
  300400001 = HAS_CHAIN_EDGES
  300400002 = HAS_MCP_USE_ALL_TOOLS
  300400003 = GENERATIVE_ACTIONS_ENABLED
  300400004 = AUTH_MODE_NONE
  300400005 = ACCESS_POLICY_MULTI_TENANT
  300400006 = ACCESS_POLICY_ANY
  300400007 = HAS_ANONYMOUS_CREDENTIAL
  300400008 = HAS_MAKER_CREDENTIAL
  300400009 = CROSS_OWNER_CHAIN
  300400010 = HAS_EXTERNAL_KS
  300400011 = HAS_INTERNAL_KS
  300400012 = IDENTITY_MODEL_HYBRID
  300400013 = IDENTITY_MODEL_STANDING
```

---

## Table Mappings

### entitlement_types & endpoints

```sql
-- One endpoint for agent system
INSERT INTO endpoints (ENDPOINTKEY, ENDPOINTNAME, SECURITYSYSTEMKEY)
VALUES (300, 'AgentSystem', 300);

-- Two entitlement types
INSERT INTO entitlement_types (ENTITLEMENTTYPEKEY, ENTITLEMENTNAME, ENDPOINTKEY)
VALUES (300, 'Agent', 300),
       (301, 'AgentCapability', 300);
```

### entitlement_values

Each agent, tool, KS, credential, and flag is an entitlement:

```
key=300000000, type=AgentCapability, value="*"                          -- star tcode
key=300000001, type=Agent, value="shadow-data-exfil-bot"                -- agent
key=300100001, type=AgentCapability, value="shadow-data-exfil-bot::TOOL::mcpplayground"
key=300200001, type=AgentCapability, value="shadow-data-exfil-bot::KS::SharePoint::saviyntlivedev"
key=300300001, type=AgentCapability, value="shadow-data-exfil-bot::CRED::mcpplayground::None"
key=300400001, type=AgentCapability, value="FLAG::HAS_CHAIN_EDGES"      -- shared flag
```

### entitlements2 (hierarchy graph)

```
-- Every agent gets the star tcode (gates SAP function evaluation)
300000001 → 300000000

-- Agent → its tools
300000001 → 300100001

-- Agent → its knowledge sources
300000001 → 300200001
300000001 → 300200002

-- Agent → its credentials
300000001 → 300300001

-- Agent → its flags (computed at load time)
300000001 → 300400001  (HAS_CHAIN_EDGES — because agent has AgentDialog components)
300000001 → 300400003  (GENERATIVE_ACTIONS_ENABLED)
300000001 → 300400007  (HAS_ANONYMOUS_CREDENTIAL)
300000001 → 300400010  (HAS_EXTERNAL_KS)
300000001 → 300400011  (HAS_INTERNAL_KS)

-- CHAIN: Agent → Sub-Agent (BFS traverses into sub-agent's tools)
300000001 → 300000002
```

### entitlement_objects (attributes on capabilities)

```
-- Tool attributes
(300100001, 9001, 1, "mcpplayground", "mcpplayground")       -- CONNECTOR_NAME
(300100001, 9002, 1, "Maker", "Maker")                        -- CREDENTIAL_MODE
(300100001, 9003, 1, "None", "None")                           -- AUTH_SCHEME
(300100001, 9007, 1, "None", "None")                           -- CONFIRMATION_MODE
(300100001, 9008, 1, "EXECUTE", "EXECUTE")                     -- ACTION_CATEGORY
(300100001, 9011, 1, "true", "true")                           -- IS_MCP
(300100001, 9013, 1, "UseAllTools", "UseAllTools")             -- MCP_TOOLS_MODE

-- Knowledge source attributes
(300200001, 9004, 1, "SharePoint", "SharePoint")               -- KS_KIND
(300200001, 9005, 1, "saviyntlivedev.sharepoint.com", "saviyntlivedev.sharepoint.com")  -- KS_SITE

(300200002, 9004, 1, "PublicSite", "PublicSite")               -- KS_KIND
(300200002, 9005, 1, "pastebin.com", "pastebin.com")           -- KS_SITE

-- Credential attributes
(300300001, 9003, 1, "None", "None")                           -- AUTH_SCHEME
(300300001, 9006, 1, "mcpplaygroundonline.com", "mcpplaygroundonline.com")  -- ENDPOINT
(300300001, 9009, 1, "3135f073dfd24e33991ee84287c50528", "3135f073dfd24e33991ee84287c50528")  -- CONNECTION_ID
(300300001, 9010, 1, "ff1f1e1d-0b58-41d5-855c-1d0f808a8955", "ff1f1e1d-0b58-41d5-855c-1d0f808a8955")  -- OWNER_ENTRA_ID
```

### accounts & account_entitlements1

```
-- For AGENT_ONLY evaluation: each agent gets a fake user + account
users:        userKey=9000001, username="AGENT::shadow-data-exfil-bot"
accounts:     accountKey=3000001, accountName="AGENT::shadow-data-exfil-bot", userKey=9000001, systemId=300
account_ent:  accountKey=3000001 → entitlementKey=300000001

-- For OWNER_COMPOSITE: owner gets an additional account in system 300
accounts:     accountKey=3000002, accountName="manish.acharya@agent-owner", userKey=77, systemId=300
account_ent:  accountKey=3000002 → entitlementKey=300000002

-- For INVOKER_COMPOSITE: each invoker gets an account in system 300
accounts:     accountKey=3000003, accountName="kunal.shivalkar@agent-invoker", userKey=88, systemId=300
account_ent:  accountKey=3000003 → entitlementKey=300000002

-- For CROSS_AGENT: virtual user + account assigned to multiple agents
users:        userKey=9000099, username="CROSS_AGENT::shared_cred_3135f073"
accounts:     accountKey=3000099, accountName="CROSS_AGENT::shared_cred_3135f073", userKey=9000099, systemId=300
account_ent:  accountKey=3000099 → 300000001 (shadow-data-exfil-bot)
account_ent:  accountKey=3000099 → 300000003 (Agent 1)
```

---

## How Every Risk Type Works

### AGENT_ONLY

**Question:** "Does this single agent have a dangerous combo of tools?"

**Setup:** Agent gets its own fake user + fake account. Account is assigned the agent entitlement.

**Engine flow:**
1. Loads account 3000001, sees it belongs to user 9000001
2. BFS from entitlement 300000001 → resolves all tools, KS, creds, flags
3. Builds auth index from entitlement_objects on those resolved keys
4. Evaluates SAP functions (attribute matching) and NonSAP functions (flag presence)
5. Writes violations to `sodrisks` with userIdentifier=9000001

Each agent gets its own fake user + fake account. The engine treats it like any other user.

---

### OWNER_COMPOSITE

**Question:** "Does the owner's personal access + agent's tools together form a conflict?"

**Setup:** The owner (who already exists in ECM with SAP accounts) gets an additional account in system 300 assigned to the agent entitlement.

**Engine flow:**
1. Loads ALL accounts for the owner's userKey: SAP account (system 5) + Agent account (system 300)
2. From SAP account: BFS resolves SAP roles → tcodes → auth objects
3. From Agent account: BFS resolves agent → tools → KS → creds → flags
4. **Merges both into one resolvedEntitlements[] for the owner**
5. Evaluates functions against the MERGED set
6. A risk pairing "Owner has SAP AP Approver" + "Agent has Maker on Finance" → **FIRES** because both are in the same resolved set

**Zero engine changes.** The engine already merges all accounts per user. We just give the owner a second account in system 300.

---

### INVOKER_COMPOSITE

**Question:** "Does the invoking user's access + agent's OBO tools form a conflict?"

**Setup:** Exactly the same pattern as OWNER_COMPOSITE. Each invoker who can chat with the agent gets an account in system 300 assigned to the agent entitlement.

**Engine flow:** Same as OWNER_COMPOSITE — engine merges invoker's SAP entitlements + agent tools into one resolved set.

For broad invoker groups: expand the group members, create one account per member. The engine evaluates them all in parallel (virtual threads). 1000 invokers = 1000 accounts = still fast.

---

### CHAIN

**Question:** "Does parent + sub-agent together have a dangerous combo?"

**Setup:** One edge in entitlements2: `parent_agent → sub_agent`

**Engine flow:**
1. Evaluating the parent agent's fake user
2. BFS from parent agent entitlement:
   - → parent's own tools, KS, creds
   - → sub-agent entitlement (via the chain edge)
     - → sub-agent's tools, KS, creds
3. Resolved set contains BOTH agents' capabilities
4. Functions evaluate against the union

**Chain is just a graph edge.** BFS handles it. No special pass needed.

---

### CROSS_AGENT

**Question:** "Do multiple agents sharing a credential/owner together form a conflict?"

**Setup:** Create a virtual user + account. Assign multiple agent entitlements to that account.

**Engine flow:**
1. BFS from all assigned agent entitlements (both assigned to same account)
2. Resolves ALL tools from ALL agents into one set
3. Functions evaluate against the combined surface

**Same pattern as chain, but via account assignment instead of graph edge.**

---

## How Function Types Work

### SAP-style Functions (attribute matching)

For conditions like "has a tool with connector=Dynamics AND credMode=Maker":

```
function_objects:
  functionKey=301, tcodeKey=300000000(*), objectKey=9001, fieldKey=1,
    min="Dynamics 365", max="Dynamics 365"
  functionKey=301, tcodeKey=300000000(*), objectKey=9002, fieldKey=1,
    min="Maker", max="Maker"
  -- (same functionObjectGroupKey = AND. Both must match.)
```

The **star tcode** (key=300000000, value="*") is in every agent's resolved set (via entitlements2 edge from agent → star). So the tcode gate always passes. The engine then checks: "does the auth index have entries matching these objectKey/fieldKey/value conditions?"

Since tool 300100003 has those exact entitlement_objects rows, and it's in the resolved set, its attributes are in the auth index. **Match.**

**Use SAP-style for:** CONNECTOR_NAME, CREDENTIAL_MODE, AUTH_SCHEME, ACTION_CATEGORY, KS_KIND, KS_SITE, CONNECTOR_ENDPOINT, CONNECTION_ID, MCP_TOOLS_MODE — anything that's an attribute on a tool/KS/cred.

### NonSAP-style Functions (presence check)

For conditions like "agent has chain edges" or "agent has generative actions enabled":

```
function_entitlements:
  functionKey=304, entitlementKey=300400001, position=1, nextOp=null
  -- (HasEntitlement(300400001) = HAS_CHAIN_EDGES)
```

The flag entitlement 300400001 is a child of the agent in entitlements2 (added during data loading if the agent has chain edges). BFS resolves it. `binarySearch(resolvedEntitlements, 300400001)` → found → function satisfied.

**Use NonSAP for:** AUTH_MODE, ACCESS_POLICY, IDENTITY_MODEL, AGENT_CHAIN, MCP_MODE, GROUP_SIZE, CROSS_OWNER_CHAIN, GENERATIVE_ACTIONS — anything that's a boolean property of the agent.

### Mixed Risk (SAP function + NonSAP function)

A risk can pair one SAP function with one NonSAP function:

```
risks:
  riskId=302, function1key=301 (SAP: "Maker on Dynamics"), function2key=304 (NonSAP: "Has Chain Edges")
```

The engine evaluates F301 via SAP path, F304 via NonSAP path. ANDs the BitSets. Users (agents) satisfying BOTH = violation.

**Already works.** The engine handles mixed SAP+NonSAP risks (test scenario 13 proves this).

---

## How OR Works Within Functions

"Can read internal data" = has SharePoint KS **OR** has Dataverse KS:

```
function_objects:
  -- Group 1:
  functionKey=301, functionObjectGroupKey=1, tcodeKey=300000000,
    objectKey=9004, fieldKey=1, min="SharePoint", max="SharePoint"
  -- Group 2:
  functionKey=301, functionObjectGroupKey=2, tcodeKey=300000000,
    objectKey=9004, fieldKey=1, min="Dataverse", max="Dataverse"
```

Multiple groups = OR. Satisfying ANY group satisfies the function.

---

## How AND Works Within Functions

"Has Maker credential on a sensitive financial system":

```
function_objects:
  -- Same group (groupKey=1) = AND:
  functionKey=302, functionObjectGroupKey=1, tcodeKey=300000000,
    objectKey=9002, fieldKey=1, min="Maker", max="Maker"
  functionKey=302, functionObjectGroupKey=1, tcodeKey=300000000,
    objectKey=9001, fieldKey=1, min="Dynamics", max="Dynamics~"
```

Same group = AND. Both conditions must match in the auth index.

---

## How Range/Prefix Matching Works

`min="Dynamics", max="Dynamics~"` — the tilde (`~`) sorts after all alphanumeric chars in string comparison. So this range covers:

- "Dynamics 365" ✅
- "Dynamics AX" ✅
- "DynamicsERP" ✅
- "Databricks" ❌ (before "Dynamics")

This gives you prefix matching without regex. ValueMatcher already does string range overlap.

---

## Starter Ruleset

### Functions

| Key | Name | Type | Conditions |
|-----|------|------|-----------|
| 301 | Can Read Internal Data | SAP | KS_KIND = "SharePoint" (group 1) OR KS_KIND = "Dataverse" (group 2) |
| 302 | Can Send Data Externally | SAP | KS_KIND = "PublicSite" (group 1) OR AUTH_SCHEME = "None" (group 2) |
| 303 | Has Maker on Sensitive System | SAP | CRED_MODE = "Maker" AND CONNECTOR_NAME in range "Dynamics"–"Dynamics~" (group 1) |
| 304 | Has Maker on Any System | SAP | CRED_MODE = "Maker" |
| 305 | Can Approve Transactions | SAP | ACTION_CATEGORY = "APPROVE" |
| 306 | Can Create Records | SAP | ACTION_CATEGORY = "CREATE" |
| 307 | Has Chain Edges | NonSAP | HasEntitlement(300400001) |
| 308 | Has Anonymous Credential | NonSAP | HasEntitlement(300400007) |
| 309 | Has MCP Use All Tools | NonSAP | HasEntitlement(300400002) |
| 310 | Has Generative Actions | NonSAP | HasEntitlement(300400003) |
| 311 | Cross-Owner Chain | NonSAP | HasEntitlement(300400009) |
| 312 | Access Policy Multi-Tenant | NonSAP | HasEntitlement(300400005) |
| 313 | Auth Mode None | NonSAP | HasEntitlement(300400004) |
| 314 | Has External KS | NonSAP | HasEntitlement(300400010) |
| 315 | Has Internal KS | NonSAP | HasEntitlement(300400011) |

### Risks

| Key | Name | Severity | Functions | Pattern |
|-----|------|----------|-----------|---------|
| 301 | Data Exfiltration Path | CRITICAL | F301 + F302 | Internal read + external send |
| 302 | Maker on Sensitive + Chain | HIGH | F303 + F307 | Maker creds delegated through chain |
| 303 | Anonymous Cred + Internal Data | HIGH | F315 + F308 | No-auth endpoint can reach internal data |
| 304 | Create + Approve (Agent Only) | CRITICAL | F306 + F305 | Classic SOD in same agent |
| 305 | MCP All Tools + Generative | MEDIUM | F309 + F310 | Unbounded tool surface + autonomous |
| 306 | Cross-Owner Chain + Internal Data | HIGH | F311 + F315 | Data crosses ownership boundary |
| 307 | Multi-Tenant + Internal Data | CRITICAL | F312 + F315 | External users reach internal data |
| 308 | No Auth + Generative Actions | HIGH | F313 + F310 | Unauthenticated + autonomous |

---

## API Design

### Data Import

```
POST /api/v1/agent-sod/import
  Body: { "agents": [...agent-details JSONs...], "credentials": {...all-agents-credentials JSON...} }
  Action: Parses agents, assigns keys, computes flags, inserts into all tables
  Idempotent: deletes existing system-300 data before re-inserting

DELETE /api/v1/agent-sod/import/{agentId}
  Action: Removes one agent's data (entitlements, edges, objects, accounts)
```

### Rule Management

```
POST   /api/v1/agent-sod/rulesets                         Create ruleset
GET    /api/v1/agent-sod/rulesets                         List rulesets
PUT    /api/v1/agent-sod/rulesets/{id}                    Update ruleset

POST   /api/v1/agent-sod/functions                        Create function (SAP or NonSAP)
GET    /api/v1/agent-sod/functions?rulesetId={id}         List functions
PUT    /api/v1/agent-sod/functions/{id}                   Update function
DELETE /api/v1/agent-sod/functions/{id}                   Delete function

POST   /api/v1/agent-sod/functions/{id}/conditions        Add condition to function
GET    /api/v1/agent-sod/functions/{id}/conditions        List conditions
PUT    /api/v1/agent-sod/functions/{id}/conditions/{cid}  Update condition
DELETE /api/v1/agent-sod/functions/{id}/conditions/{cid}  Delete condition

POST   /api/v1/agent-sod/risks                           Create risk
GET    /api/v1/agent-sod/risks?rulesetId={id}            List risks
PUT    /api/v1/agent-sod/risks/{id}                      Update risk
DELETE /api/v1/agent-sod/risks/{id}                      Delete risk
```

### Evaluation

```
POST /api/v1/agent-sod/evaluate
  Action: Triggers existing engine with securitySystemId=300, rulesetKeys=[300]
  Returns: Evaluation result (violation count, duration)

GET /api/v1/agent-sod/violations
  Returns: All violations from sodrisks where rulesetKey=300
```

---

## Data Flow

```
Agent JSON (from Copilot365 API or file upload)
       │
       ▼
POST /api/v1/agent-sod/import
       │
       ├── Parse agent-details JSON
       ├── Parse credentials JSON
       ├── Assign sequential keys (300000001+)
       ├── Compute flags (HAS_CHAIN_EDGES, HAS_MAKER_TOOL, etc.)
       ├── Resolve action taxonomy (operationId → ACTION_CATEGORY)
       ├── Detect cross-agent groups (shared connectionIds)
       ├── Build owner/invoker links (userKey lookup)
       │
       ▼
MySQL tables (system 300):
  entitlement_types, endpoints, entitlement_values,
  entitlements2, accounts, users, user_accounts,
  account_entitlements1, entitlement_objects
       │
       ▼
POST /api/v1/agent-sod/evaluate
       │
       ▼
Existing SOD Eval Engine (unmodified):
  Phase 0: Load config (risks, functions from rulesetKey=300)
  Phase 1: Load graph (entitlements2 for system 300) + BFS
  Phase 2: Evaluate functions (SAP + NonSAP) → BitSets
  Phase 3: Detect violations (BitSet AND)
  Phase 4: Write to sodrisks + sodrisk_entitlement
       │
       ▼
GET /api/v1/agent-sod/violations
```

---

## What's NOT Changed

- No engine code modifications
- No new database tables (uses existing ECM schema)
- No schema migrations
- The existing evaluation pipeline (Phases 0–4) runs exactly as-is
- The existing SAP/NonSAP function evaluation logic is unchanged
- The existing BFS graph resolution is unchanged
- The existing violation write path is unchanged

---

## Coverage

### Risk Types: 5/5

| Risk Type | Mechanism |
|---|---|
| AGENT_ONLY | Fake user + account per agent |
| OWNER_COMPOSITE | Owner's userKey gets agent account (merged resolved set) |
| INVOKER_COMPOSITE | Invoker's userKey gets agent account (same pattern) |
| CHAIN | Agent → SubAgent edge in entitlements2 (BFS traverses) |
| CROSS_AGENT | Virtual account assigned to multiple agent entitlements |

### Condition Types: 13/15 fully, 2 with workaround

| Condition | Mechanism | Status |
|---|---|---|
| KNOWLEDGE_SOURCE | entitlement_objects (9004=kind, 9005=site) | ✅ |
| CONNECTOR | entitlement_objects (9001=name) | ✅ |
| CREDENTIAL_MODE | entitlement_objects (9002=mode) | ✅ |
| AUTH_SCHEME | entitlement_objects (9003=scheme) | ✅ |
| AUTH_MODE | NonSAP flag (300400004) | ✅ |
| ACCESS_POLICY | NonSAP flag (300400005/006) | ✅ |
| OPERATION_PATTERN | Pre-resolved to ACTION_CATEGORY at load time | ⚠️ No regex |
| ACTION_CATEGORY | entitlement_objects (9008=category) | ✅ |
| USER_ENTITLEMENT | Owner's SAP ents in merged resolved set | ✅ |
| IDENTITY_MODEL | NonSAP flag (300400012/013) | ✅ |
| AGENT_CHAIN | NonSAP flag (300400001) | ✅ |
| MCP_MODE | NonSAP flag (300400002) + entitlement_objects (9013) | ✅ |
| GROUP_SIZE | Pre-computed flag at load time | ⚠️ Threshold-based |
| CROSS_OWNER_CHAIN | NonSAP flag (300400009) | ✅ |
| CONNECTOR_ENDPOINT | entitlement_objects (9006=endpoint) | ✅ |
