# Agent SOD → SOD Evaluation Engine: Data Mapping Spec

## Goal

Reuse the existing SOD evaluation engine (BFS graph resolution, BitSet-based function evaluation, parallel virtual threads, delta writes) to evaluate AI agent SOD violations. Map Microsoft Copilot Studio agent data into the engine's existing data model.

## Architecture Decision

**Agent as an entitlement assigned to its owner.** This enables:
- Owner composite SOD falls out naturally from BFS (owner's IGA ents + agent tools in one resolved set)
- Chain SOD via graph traversal (agent → sub-agent → sub-agent's tools)
- Invoker composite same pattern as owner composite
- Existing engine unchanged — only a data loading/mapping layer is added

## Data Model Mapping

### Hierarchy (entitlements2)

```
Owner_Account → Agent_Entitlement          (owner "has" this agent)
Agent_Entitlement → Tool_1                 (agent has this tool)
Agent_Entitlement → Tool_2
Agent_Entitlement → KnowledgeSource_1      (agent has this knowledge source)
Agent_Entitlement → Credential_1           (agent has this credential binding)
Agent_Entitlement → SubAgent_X             (agent invokes sub-agent — CHAIN)
SubAgent_X → SubAgent_Tool_1              (sub-agent's tools — resolved via BFS)
SubAgent_X → SubAgent_Tool_2
```

### Account assignments (account_entitlements1)

```
Owner_Account → Agent_Entitlement          (for OWNER_COMPOSITE evaluation)
Invoker_Account → Agent_Entitlement        (for INVOKER_COMPOSITE evaluation)
Agent_As_Account → Tool_1, Tool_2, ...     (for AGENT_ONLY evaluation — agent IS the account)
```

### Tool/capability attributes (entitlement_objects)

Each tool/knowledge source/credential gets attribute rows:

```
Tool_1 → (objectKey=CONNECTOR, fieldKey=NAME, min="Dynamics 365", max="Dynamics 365")
Tool_1 → (objectKey=CREDENTIAL_MODE, fieldKey=MODE, min="Maker", max="Maker")
Tool_1 → (objectKey=AUTH_SCHEME, fieldKey=SCHEME, min="OAuth2", max="OAuth2")
Tool_1 → (objectKey=OPERATION, fieldKey=ID, min="CreateInvoice", max="CreateInvoice")
Tool_1 → (objectKey=ACTION_CATEGORY, fieldKey=CATEGORY, min="CREATE", max="CREATE")
Tool_1 → (objectKey=CONFIRMATION, fieldKey=MODE, min="None", max="None")

KnowledgeSource_1 → (objectKey=KS_KIND, fieldKey=TYPE, min="SharePoint", max="SharePoint")
KnowledgeSource_1 → (objectKey=KS_SITE, fieldKey=URL, min="sharepoint.com/internal", max="sharepoint.com/internal")

Credential_1 → (objectKey=ENDPOINT, fieldKey=URL, min="mcpplayground.com", max="mcpplayground.com")
Credential_1 → (objectKey=AUTH_SCHEME, fieldKey=SCHEME, min="None", max="None")
Credential_1 → (objectKey=CONNECTION_ID, fieldKey=ID, min="3135f073...", max="3135f073...")

Agent_Entitlement → (objectKey=AUTH_MODE, fieldKey=MODE, min="None", max="None")
Agent_Entitlement → (objectKey=ACCESS_POLICY, fieldKey=POLICY, min="MultiTenant", max="MultiTenant")
Agent_Entitlement → (objectKey=IDENTITY_MODEL, fieldKey=MODEL, min="HYBRID", max="HYBRID")
```

### Synthetic entitlements (for structural conditions)

```
Agent_Entitlement → HAS_CHAIN_EDGES        (if agent has AgentDialog components)
Agent_Entitlement → MCP_USE_ALL_TOOLS      (if any tool has mcpToolsMode=UseAllTools)
Agent_Entitlement → CROSS_OWNER_CHAIN      (if chain spans multiple owners — computed during loading)
```

### Function definitions (function_objects)

```
Function "Can Read Internal Data":
  tcodeKey=ANY_TOOL, objectKey=KS_KIND, fieldKey=TYPE, min="SharePoint", max="SharePoint"
  OR
  tcodeKey=ANY_TOOL, objectKey=KS_KIND, fieldKey=TYPE, min="Dataverse", max="Dataverse"

Function "Can Send Data Externally":
  tcodeKey=ANY_TOOL, objectKey=CONNECTOR, fieldKey=NAME, min="HTTP", max="HTTP"
  OR
  tcodeKey=ANY_TOOL, objectKey=ENDPOINT, fieldKey=URL, min="*external*", max="*external*"

Function "Has Maker Credential on Sensitive System":
  tcodeKey=ANY_TOOL, objectKey=CREDENTIAL_MODE, fieldKey=MODE, min="Maker", max="Maker"
  AND
  tcodeKey=ANY_TOOL, objectKey=CONNECTOR, fieldKey=NAME, min="Dynamics%", max="Dynamics%"

Function "Owner Has AP Approver Role":
  (This is a NonSAP-style condition — just checks if owner has the IGA entitlement)
  entitlementKey = SAP_AP_APPROVER_ROLE_KEY
```

### Risk definitions

```
Risk "Data Exfiltration Path":
  function1 = "Can Read Internal Data"
  function2 = "Can Send Data Externally"
  riskType = AGENT_ONLY

Risk "Owner-Agent Payment Fraud":
  function1 = "Has Maker Credential on Sensitive System"  (target: AGENT)
  function2 = "Owner Has AP Approver Role"                (target: OWNER — in owner's resolved ents)
  riskType = OWNER_COMPOSITE
```

## Cross-Agent Handling (Pre-processing)

Before evaluation, for CROSS_AGENT risks:
1. Group agents by shared attribute (connectionId, ownerEntraId)
2. For each group with size > 1:
   - Create a virtual "group agent" entitlement
   - Add all group members' tools as children in entitlements2
   - Add GROUP_SIZE_GT_N synthetic entitlement
3. Evaluate the virtual group agent against CROSS_AGENT risks

## Data Loading Flow

```
1. Fetch agent data from Copilot365 API (or JSON files)
2. For each agent:
   a. Create entitlement_values entry (the agent itself)
   b. Create entitlement_values entries for each tool, knowledge source, credential
   c. Create entitlements2 edges: agent → tools/knowledge/credentials
   d. Create entitlement_objects rows for each attribute of each tool
   e. If agent has chain edges: create entitlements2 edges to sub-agents
   f. Create account_entitlements1: owner_account → agent
3. For CROSS_AGENT: create virtual group entities (pre-processing)
4. Load owner entitlements from IGA (ECM API or cross-schema query)
5. Run evaluation (same engine — Phase 0 through Phase 4)
```

## What the Implementor Needs to Verify

1. **Exact JSON structure** of agent-details.json — field paths for tools, credentials, knowledge sources
2. **How chain edges reference sub-agents** — by name? by ID? Need to resolve to actual agent data
3. **Credential connectionId format** — used for CROSS_AGENT grouping
4. **Owner entraId resolution** — how to join to IGA warehouse
5. **Whether ValueMatcher needs regex support** for OPERATION_PATTERN conditions
6. **Star tcode equivalent** — is there an "any tool" wildcard concept needed?

## Key Files to Read

- `/Users/arin.mallanna/Downloads/handoff/TOOLS_HANDOFF.md` — full data format spec
- `/Users/arin.mallanna/Downloads/handoff/NHI_LINEAGE_HANDOFF.md` — chain/graph structure
- `/Users/arin.mallanna/Downloads/handoff/samples/accessreviewagent/03-agent-details.json` — real agent data
- `/Users/arin.mallanna/Downloads/handoff/samples/shadow-data-exfil-bot/03-agent-details.json` — exfiltration patterns
- `/Users/arin.mallanna/Downloads/handoff/samples/_shared/all-agents-credentials.json` — cross-agent credential sharing
- `/Users/arin.mallanna/AAG/agent-sod-service/src/main/java/com/saviynt/agentsod/boundary/AgentDataLoader.java` — existing parser

## Relationship to Existing Agent-SOD-Service

The agent-sod-service at `/Users/arin.mallanna/AAG/agent-sod-service` already has:
- `AgentDataLoader.java` — parses agent JSON into `Agent` entity
- `ConditionEvaluator.java` — 15 condition types evaluated
- `EvaluationService.java` — 3-pass engine

The new approach replaces the evaluation engine with the SOD eval service's engine (faster, BitSet-based) but can reuse the data loading/parsing from agent-sod-service.
