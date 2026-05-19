# LLM Handoff: Agent SOD Data Mapping Implementation

## Who You Are Helping

Arin Mallanna — backend dev at Saviynt. Building a system that evaluates Segregation of Duties (SOD) violations for AI agents (Microsoft Copilot Studio bots). The goal is to reuse an existing high-performance SOD evaluation engine (built for human users with SAP/NonSAP roles) and feed it agent data instead.

## What Already Exists

### 1. SOD Evaluation Engine (DONE, OPTIMIZED)
Location: `/Users/arin.mallanna/AAG/sod-evaluation-service`

A Java 21 / Spring Boot 3.5 microservice that detects SOD violations. It:
- Loads a role hierarchy graph from MySQL (`entitlements2` table — parent→child edges)
- Resolves each user's effective entitlements via BFS through the graph
- Evaluates "functions" (capability definitions) against users using BitSets
- Detects violations by AND-ing function BitSets across risk definitions
- Writes results with hash-based delta detection (only writes changes)

Key tables it uses:
- `entitlements2` — hierarchy graph (parent_key → child_key)
- `account_entitlements1` — direct assignments (account → entitlement)
- `entitlement_objects` — attributes per entitlement (role → objectKey, fieldKey, minValue, maxValue)
- `function_objects` — SAP function conditions (function → tcode + object + field + value range)
- `function_entitlements` — NonSAP boolean conditions (function → entitlement with AND/OR operators)
- `risks` — conflict definitions (risk → function1 + function2 + ... function5)
- `sodrisks` / `sodrisk_entitlement` — violation output

Key optimizations already implemented (2026-05-19):
- Function-scoped subgraph via recursive CTE (only loads relevant graph edges)
- Auth filtering by function-referenced (objectKey, fieldKey) pairs
- Per-user auth index caching (built once, reused across all functions)
- Excluded edges removed at graph construction
- Hash-based delta writes (only writes changed violations)
- Full path evidence stored in PARENTROLEKEYASCSV
- Per-account attribution

### 2. Agent SOD Service (PROTOTYPE, SEPARATE ENGINE)
Location: `/Users/arin.mallanna/AAG/agent-sod-service`

A separate prototype that evaluates agent SOD using its own 3-pass engine. It has:
- `AgentDataLoader.java` — parses Microsoft agent JSON into `Agent` entity
- `ConditionEvaluator.java` — evaluates 15 condition types against agents
- `EvaluationService.java` — 3-pass engine (per-agent, cross-agent, chain)
- Full CRUD APIs for rules, functions, conditions, risks
- Violation lifecycle management (open/close/mitigate/reopen)

This prototype works but is NOT the target architecture. The goal is to feed agent data into the SOD evaluation engine (#1) instead.

### 3. Agent Data (FROM MICROSOFT)
Location: `/Users/arin.mallanna/Downloads/handoff/`

Raw data from Microsoft Copilot Studio containing:
- Per-agent tool inventory (connectors, MCP servers, knowledge sources)
- Credential bindings (OAuth, API key, anonymous)
- Agent-to-agent invocation edges (chain topology)
- Owner identity (Entra ID → maps to IGA warehouse)
- Bot governance config (auth mode, access policy, authorized groups)

Key files:
- `TOOLS_HANDOFF.md` — complete data format spec (what fields exist, where they come from)
- `NHI_LINEAGE_HANDOFF.md` — graph structure for chain detection
- `samples/accessreviewagent/03-agent-details.json` — real agent with 22 tools
- `samples/shadow-data-exfil-bot/03-agent-details.json` — malicious agent pattern
- `samples/_shared/all-agents-credentials.json` — cross-agent credential sharing
- `samples/_shared/dv-connectors.json` — tenant connector catalog

### 4. PRD & Gap Analysis
- PRD: `/Users/arin.mallanna/Downloads/Agent_SOD_PRD_v6.docx` (convert with `textutil -convert txt -stdout`)
- Gap Analysis: `/Users/arin.mallanna/Downloads/Agent_SOD_PRD_DataGap_Analysis_v2.docx`

77 requirements across: Preventive (tool conflict, owner composite, invoker screening, chain analysis), Runtime (PEP — out of scope for now), Detective (continuous monitoring), Autonomy Tiers.

## The Decided Approach

**Treat the agent as an entitlement assigned to its owner.** Map agent capabilities into the SOD eval engine's existing tables. The mapping spec is at:
`/Users/arin.mallanna/AAG/sod-evaluation-service/docs/AGENT_SOD_DATA_MAPPING_SPEC.md`

### Core mapping:
```
Owner (user in IGA) → has Account → assigned Agent (as entitlement)
Agent (entitlement) → has children: Tool_1, Tool_2, KnowledgeSource_1, Credential_1, SubAgent_X
Each tool/knowledge/credential has ATTRIBUTES stored in entitlement_objects:
  Tool_1 → (CONNECTOR, NAME, "Dynamics 365")
  Tool_1 → (CREDENTIAL_MODE, MODE, "Maker")
  Tool_1 → (ACTION_CATEGORY, CATEGORY, "APPROVE")
  KnowledgeSource_1 → (KS_KIND, TYPE, "SharePoint")
```

### Why this works for all 5 risk types:
1. **AGENT_ONLY** — agent is a "user", its tools are resolved entitlements, evaluate functions against them
2. **OWNER_COMPOSITE** — owner's IGA entitlements + agent tools both in resolved set (BFS merges them naturally)
3. **INVOKER_COMPOSITE** — same as owner composite but for each invoker
4. **CHAIN** — agent → sub-agent edges in entitlements2, BFS traverses the chain automatically
5. **CROSS_AGENT** — pre-processing step: group agents by shared credential/owner, create virtual merged entity

### What needs to be built:
1. **AgentToSodMapper** — reads agent JSON, writes to SOD eval engine tables (entitlements2, account_entitlements1, entitlement_objects)
2. **Agent function/risk definitions** — define the SOD rules in function_objects format (what tool combinations are violations)
3. **Pre-processing for CROSS_AGENT** — group agents, create virtual entities
4. **Taxonomy resolution** — map operationId → ACTION_CATEGORY during data loading
5. **Synthetic entitlements** — for structural conditions (HAS_CHAIN_EDGES, MCP_USE_ALL_TOOLS, CROSS_OWNER_CHAIN)

### Known gaps that need resolution:
- OPERATION_PATTERN (regex matching) — ValueMatcher currently does range overlap, not regex
- GROUP_SIZE condition — needs synthetic entitlement approach
- Whether to use a separate securitySystemId for agent data (recommended: yes, keeps it isolated)

## Your Task

1. Read the full handoff data (TOOLS_HANDOFF.md, NHI_LINEAGE_HANDOFF.md, sample JSONs) to understand the exact JSON structure
2. Read the mapping spec at `docs/AGENT_SOD_DATA_MAPPING_SPEC.md`
3. Validate the mapping against actual data structures — are there fields that don't fit? Edge cases?
4. Build the `AgentToSodMapper` that:
   - Parses agent JSON (reuse patterns from `agent-sod-service/AgentDataLoader.java`)
   - Creates entitlement_values entries for each agent, tool, knowledge source, credential
   - Creates entitlements2 edges (agent → tools, agent → sub-agents)
   - Creates entitlement_objects rows for tool attributes
   - Creates account_entitlements1 entries (owner → agent, agent_as_account → tools)
   - Handles chain edges and cross-agent grouping
5. Define sample function_objects and risks that detect the patterns from the PRD (data exfiltration, owner composite payment fraud, chain privilege escalation)
6. Test against the sample agent data (accessreviewagent + shadow-data-exfil-bot)

## Key Constraints

- Don't modify the evaluation engine code (EvaluationOrchestrator, FunctionEvaluationService, etc.) — only add a data mapping layer
- Use a separate securitySystemId (e.g., 300) for agent data to isolate from human SOD
- The agent-sod-service's `ConditionEvaluator.java` has the condition evaluation logic for reference — but we're replacing it with the SOD eval engine's function evaluation
- Agent data is tiny (34 agents, ~20 tools each) — performance is not a concern here, correctness is

## Files to Read (in order)

1. `/Users/arin.mallanna/AAG/sod-evaluation-service/docs/AGENT_SOD_DATA_MAPPING_SPEC.md`
2. `/Users/arin.mallanna/Downloads/handoff/TOOLS_HANDOFF.md` (FULL — all sections)
3. `/Users/arin.mallanna/Downloads/handoff/NHI_LINEAGE_HANDOFF.md` (FULL)
4. `/Users/arin.mallanna/Downloads/handoff/samples/accessreviewagent/03-agent-details.json`
5. `/Users/arin.mallanna/Downloads/handoff/samples/shadow-data-exfil-bot/03-agent-details.json`
6. `/Users/arin.mallanna/Downloads/handoff/samples/_shared/all-agents-credentials.json`
7. `/Users/arin.mallanna/AAG/agent-sod-service/src/main/java/com/saviynt/agentsod/boundary/AgentDataLoader.java`
8. `/Users/arin.mallanna/AAG/agent-sod-service/src/main/java/com/saviynt/agentsod/entity/Agent.java`
9. `/Users/arin.mallanna/AAG/agent-sod-service/src/main/java/com/saviynt/agentsod/entity/Tool.java`
10. `/Users/arin.mallanna/AAG/agent-sod-service/src/main/java/com/saviynt/agentsod/entity/KnowledgeSource.java`
11. `/Users/arin.mallanna/AAG/agent-sod-service/src/main/java/com/saviynt/agentsod/entity/CredentialBinding.java`
12. `/Users/arin.mallanna/AAG/sod-evaluation-service/src/main/java/com/saviynt/sod/evaluation/dao/AccessDataDao.java`
13. `/Users/arin.mallanna/AAG/sod-evaluation-service/src/main/java/com/saviynt/sod/evaluation/dao/SodConfigDao.java`
14. `/Users/arin.mallanna/AAG/sod-evaluation-service/src/main/java/com/saviynt/sod/evaluation/model/` (all records)
15. `/Users/arin.mallanna/AAG/sod-evaluation-service/setup-db.sql` or Flyway migration for table schemas
