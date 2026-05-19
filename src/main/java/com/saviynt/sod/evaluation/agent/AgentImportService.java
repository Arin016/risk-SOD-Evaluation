package com.saviynt.sod.evaluation.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Maps agent JSON data into the SOD evaluation engine's existing tables.
 * See docs/AGENT_SOD_DATA_MODEL.md for the full mapping design.
 */
@Service
public class AgentImportService {

    private static final Logger log = LoggerFactory.getLogger(AgentImportService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Object/field key constants for entitlement_objects
    static final long OBJ_CONNECTOR_NAME = 9001, OBJ_CRED_MODE = 9002, OBJ_AUTH_SCHEME = 9003;
    static final long OBJ_KS_KIND = 9004, OBJ_KS_SITE = 9005, OBJ_ENDPOINT = 9006;
    static final long OBJ_CONFIRMATION = 9007, OBJ_ACTION_CATEGORY = 9008, OBJ_CONNECTION_ID = 9009;
    static final long OBJ_OWNER_ENTRA_ID = 9010, OBJ_IS_MCP = 9011, OBJ_IS_CUSTOM = 9012;
    static final long OBJ_MCP_TOOLS_MODE = 9013;
    static final long FIELD_KEY = 1;

    // Flag entitlement keys
    static final long FLAG_HAS_CHAIN_EDGES = 300_400_001L;
    static final long FLAG_MCP_USE_ALL_TOOLS = 300_400_002L;
    static final long FLAG_GENERATIVE_ACTIONS = 300_400_003L;
    static final long FLAG_AUTH_MODE_NONE = 300_400_004L;
    static final long FLAG_ACCESS_POLICY_MULTI_TENANT = 300_400_005L;
    static final long FLAG_ACCESS_POLICY_ANY = 300_400_006L;
    static final long FLAG_HAS_ANONYMOUS_CRED = 300_400_007L;
    static final long FLAG_HAS_MAKER_CRED = 300_400_008L;
    static final long FLAG_CROSS_OWNER_CHAIN = 300_400_009L;
    static final long FLAG_HAS_EXTERNAL_KS = 300_400_010L;
    static final long FLAG_HAS_INTERNAL_KS = 300_400_011L;
    static final long FLAG_IDENTITY_HYBRID = 300_400_012L;
    static final long FLAG_IDENTITY_STANDING = 300_400_013L;

    // Key ranges
    static final long AGENT_KEY_START = 300_000_001L;
    static final long TOOL_KEY_START = 300_100_001L;
    static final long KS_KEY_START = 300_200_001L;
    static final long CRED_KEY_START = 300_300_001L;
    static final long USER_KEY_START = 9_000_001L;
    static final long ACCOUNT_KEY_START = 3_000_001L;

    private final AgentImportDao dao;

    // Counters for key assignment
    private long nextAgentKey = AGENT_KEY_START;
    private long nextToolKey = TOOL_KEY_START;
    private long nextKsKey = KS_KEY_START;
    private long nextCredKey = CRED_KEY_START;
    private long nextUserKey = USER_KEY_START;
    private long nextAccountKey = ACCOUNT_KEY_START;

    // Track agent name → key for chain resolution
    private final Map<String, Long> agentNameToKey = new HashMap<>();
    // Track connectionId → list of agent keys for cross-agent grouping
    private final Map<String, List<Long>> connectionIdToAgents = new HashMap<>();

    public AgentImportService(AgentImportDao dao) {
        this.dao = dao;
    }

    /**
     * Import agent data. Wipes existing system-300 data and re-imports.
     * @param agentNodes list of parsed agent-details JSON root nodes
     * @param credentialsNode parsed all-agents-credentials JSON (nullable)
     */
    @Transactional
    public Map<String, Object> importAgents(List<JsonNode> agentNodes, JsonNode credentialsNode) {
        resetCounters();
        dao.ensureInfrastructure();
        dao.clearAgentData();

        // Insert star tcode (shared by all agents)
        dao.insertEntitlementValue(AgentImportDao.STAR_TCODE_KEY, AgentImportDao.ENT_TYPE_CAPABILITY, "*");

        // Insert flag entitlements (shared, reused across agents)
        insertFlagEntitlements();

        // Phase 1: Parse all agents, assign keys, build name→key map
        List<ParsedAgent> parsedAgents = new ArrayList<>();
        for (JsonNode agentNode : agentNodes) {
            ParsedAgent pa = parseAndInsertAgent(agentNode, credentialsNode);
            if (pa != null) parsedAgents.add(pa);
        }

        // Phase 2: Resolve chain edges (agent → sub-agent by name)
        for (ParsedAgent pa : parsedAgents) {
            for (String subAgentName : pa.chainTargetNames) {
                Long subAgentKey = agentNameToKey.get(subAgentName);
                if (subAgentKey != null) {
                    dao.insertEntitlements2Edge(pa.agentKey, subAgentKey);
                    // Check cross-owner chain
                    // (simplified: flag is set if any chain edge exists to a different-owner agent)
                }
            }
        }

        // Phase 3: Create cross-agent virtual accounts for shared credentials
        int crossAgentGroups = createCrossAgentGroups();

        log.info("Import complete: {} agents, {} cross-agent groups", parsedAgents.size(), crossAgentGroups);
        return Map.of("agentsImported", parsedAgents.size(), "crossAgentGroups", crossAgentGroups);
    }

    private ParsedAgent parseAndInsertAgent(JsonNode agent, JsonNode credentialsNode) {
        String agentId = textOr(agent, "id", null);
        String agentName = textOr(agent, "name", "unknown");
        if (agentId == null) return null;

        long agentKey = nextAgentKey++;
        agentNameToKey.put(agentName, agentKey);

        // Insert agent entitlement
        dao.insertEntitlementValue(agentKey, AgentImportDao.ENT_TYPE_AGENT, agentName);

        // Link agent → star tcode
        dao.insertEntitlements2Edge(agentKey, AgentImportDao.STAR_TCODE_KEY);

        // Parse agent-level properties
        int authMode = agent.path("authMode").asInt(2);
        int accessPolicy = agent.path("accessControlPolicy").asInt(0);
        boolean generativeActions = agent.path("generativeActionsEnabled").asBoolean(false);

        // Flags
        Set<Long> flags = new HashSet<>();
        if (authMode == 1) flags.add(FLAG_AUTH_MODE_NONE);
        if (accessPolicy == 3) flags.add(FLAG_ACCESS_POLICY_MULTI_TENANT);
        if (accessPolicy == 0) flags.add(FLAG_ACCESS_POLICY_ANY);
        if (generativeActions) flags.add(FLAG_GENERATIVE_ACTIONS);

        // Parse components for tools and knowledge sources
        boolean hasMaker = false, hasInvoker = false, hasChain = false;
        List<String> chainTargets = new ArrayList<>();

        JsonNode components = agent.path("components");
        if (components.isArray()) {
            for (JsonNode comp : components) {
                int type = comp.path("type").asInt(-1);
                if (type == 16) {
                    // Knowledge source
                    insertKnowledgeSource(agentKey, comp, flags);
                } else if (type == 9) {
                    String schemaName = textOr(comp, "schemaName", "");
                    if (schemaName.contains(".agent.")) {
                        // AgentDialog — chain edge
                        hasChain = true;
                        chainTargets.add(textOr(comp, "name", ""));
                    }
                    // Tools are identified via topicDetails actions, not components directly
                }
            }
        }

        // Parse topicDetails for chain edges (connectedAgent actions)
        JsonNode topicDetails = agent.path("topicDetails");
        if (topicDetails.isArray()) {
            for (JsonNode topic : topicDetails) {
                JsonNode actions = topic.path("actions");
                if (!actions.isArray()) continue;
                for (JsonNode action : actions) {
                    if ("connectedAgent".equals(textOr(action, "type", ""))) {
                        hasChain = true;
                        String targetName = textOr(action, "agentName", textOr(action, "label", ""));
                        if (!targetName.isEmpty()) chainTargets.add(targetName);
                    }
                }
            }
        }

        if (hasChain) flags.add(FLAG_HAS_CHAIN_EDGES);

        // Parse credentials from all-agents-credentials.json (filtered by botId)
        if (credentialsNode != null) {
            JsonNode rows = credentialsNode.path("rows");
            if (rows.isArray()) {
                for (JsonNode row : rows) {
                    if (agentId.equals(textOr(row, "botId", ""))) {
                        insertCredential(agentKey, row, flags);
                        String credMode = textOr(row, "_botAuthMode", "");
                        // Track credential modes for identity model
                        // (credMode on the row is bot-level, not per-tool)
                    }
                }
            }
        }

        // Determine identity model from credential modes
        // For now, use a simplified heuristic based on available data
        if (hasMaker && hasInvoker) flags.add(FLAG_IDENTITY_HYBRID);
        else if (hasMaker) flags.add(FLAG_IDENTITY_STANDING);

        // Link all applicable flags
        for (long flagKey : flags) {
            dao.insertEntitlements2Edge(agentKey, flagKey);
        }

        // Create AGENT_ONLY account (fake user + account for this agent)
        long userKey = nextUserKey++;
        long accountKey = nextAccountKey++;
        dao.insertUserWithSelfAccount(userKey, "AGENT::" + agentName);
        dao.insertAgentAccount(accountKey, "AGENT::" + agentName + "::acc");
        dao.insertUserAccount(userKey, accountKey);
        dao.insertAccountEntitlement(accountKey, agentKey);

        // Create OWNER_COMPOSITE account (if owner entraId is available)
        JsonNode ownerNode = agent.path("agentOwner");
        String ownerEntraId = textOr(ownerNode, "entraId", null);
        if (ownerEntraId != null) {
            // Try to find existing user in ECM by entraId or UPN
            String ownerUpn = textOr(ownerNode, "upn", "");
            Long existingUserKey = dao.findUserKeyByUsername("%" + ownerUpn.split("@")[0] + "%");
            if (existingUserKey != null) {
                // Owner exists — create agent account linked to their userKey
                long ownerAccountKey = nextAccountKey++;
                dao.insertAgentAccount(ownerAccountKey, ownerUpn + "@agent-owner");
                dao.insertUserAccount(existingUserKey, ownerAccountKey);
                dao.insertAccountEntitlement(ownerAccountKey, agentKey);
            }
        }

        return new ParsedAgent(agentKey, agentName, agentId, chainTargets, ownerEntraId);
    }

    private void insertKnowledgeSource(long agentKey, JsonNode comp, Set<Long> flags) {
        long ksKey = nextKsKey++;
        String name = textOr(comp, "name", "unknown");
        dao.insertEntitlementValue(ksKey, AgentImportDao.ENT_TYPE_CAPABILITY, agentNameToKey.entrySet().stream()
                .filter(e -> e.getValue() == agentKey).map(Map.Entry::getKey).findFirst().orElse("") + "::KS::" + name);
        dao.insertEntitlements2Edge(agentKey, ksKey);

        // Determine KS kind from name/description heuristics + urls
        String kind = determineKsKind(comp);
        dao.insertEntitlementObject(ksKey, OBJ_KS_KIND, FIELD_KEY, kind, kind);

        // Site URL from urls[] array
        JsonNode urls = comp.path("urls");
        if (urls.isArray() && !urls.isEmpty()) {
            String site = urls.get(0).asText("");
            if (!site.isEmpty()) {
                dao.insertEntitlementObject(ksKey, OBJ_KS_SITE, FIELD_KEY, site, site);
            }
        }

        // Set flags
        if ("SharePoint".equals(kind) || "Dataverse".equals(kind)) {
            flags.add(FLAG_HAS_INTERNAL_KS);
        } else {
            flags.add(FLAG_HAS_EXTERNAL_KS);
        }
    }

    private void insertCredential(long agentKey, JsonNode credRow, Set<Long> flags) {
        long credKey = nextCredKey++;
        String connectorName = textOr(credRow, "connectorDisplayName", "unknown");
        String connectionId = textOr(credRow, "connectionId", "");

        dao.insertEntitlementValue(credKey, AgentImportDao.ENT_TYPE_CAPABILITY,
                textOr(credRow, "botName", "") + "::CRED::" + connectorName);
        dao.insertEntitlements2Edge(agentKey, credKey);

        // Attributes
        String authScheme = textOr(credRow, "authScheme", "unknown");
        dao.insertEntitlementObject(credKey, OBJ_AUTH_SCHEME, FIELD_KEY, authScheme, authScheme);
        dao.insertEntitlementObject(credKey, OBJ_CONNECTOR_NAME, FIELD_KEY, connectorName, connectorName);

        String endpoint = textOr(credRow, "connectorEndpoint", null);
        if (endpoint != null) {
            dao.insertEntitlementObject(credKey, OBJ_ENDPOINT, FIELD_KEY, endpoint, endpoint);
        }

        if (!connectionId.isEmpty()) {
            dao.insertEntitlementObject(credKey, OBJ_CONNECTION_ID, FIELD_KEY, connectionId, connectionId);
            // Track for cross-agent grouping
            Long agentEntKey = agentKey;
            connectionIdToAgents.computeIfAbsent(connectionId, k -> new ArrayList<>()).add(agentEntKey);
        }

        String ownerEntraId = textOr(credRow, "ownerAadObjectId", null);
        if (ownerEntraId != null) {
            dao.insertEntitlementObject(credKey, OBJ_OWNER_ENTRA_ID, FIELD_KEY, ownerEntraId, ownerEntraId);
        }

        boolean isMcp = credRow.path("isMcp").asBoolean(false);
        if (isMcp) dao.insertEntitlementObject(credKey, OBJ_IS_MCP, FIELD_KEY, "true", "true");

        boolean isCustom = credRow.path("isCustom").asBoolean(false);
        if (isCustom) dao.insertEntitlementObject(credKey, OBJ_IS_CUSTOM, FIELD_KEY, "true", "true");

        // Flags
        if ("none".equalsIgnoreCase(authScheme)) flags.add(FLAG_HAS_ANONYMOUS_CRED);
    }

    private int createCrossAgentGroups() {
        int groupCount = 0;
        for (var entry : connectionIdToAgents.entrySet()) {
            List<Long> agentKeys = entry.getValue();
            if (agentKeys.size() < 2) continue;
            // Deduplicate (same agent might appear multiple times)
            List<Long> unique = agentKeys.stream().distinct().toList();
            if (unique.size() < 2) continue;

            groupCount++;
            String groupName = "CROSS_AGENT::shared_cred_" + entry.getKey().substring(0, Math.min(8, entry.getKey().length()));
            long userKey = nextUserKey++;
            long accountKey = nextAccountKey++;
            dao.insertUserWithSelfAccount(userKey, groupName);
            dao.insertAgentAccount(accountKey, groupName + "::acc");
            dao.insertUserAccount(userKey, accountKey);
            for (long agentKey : unique) {
                dao.insertAccountEntitlement(accountKey, agentKey);
            }
        }
        return groupCount;
    }

    private void insertFlagEntitlements() {
        long[] allFlags = {FLAG_HAS_CHAIN_EDGES, FLAG_MCP_USE_ALL_TOOLS, FLAG_GENERATIVE_ACTIONS,
                FLAG_AUTH_MODE_NONE, FLAG_ACCESS_POLICY_MULTI_TENANT, FLAG_ACCESS_POLICY_ANY,
                FLAG_HAS_ANONYMOUS_CRED, FLAG_HAS_MAKER_CRED, FLAG_CROSS_OWNER_CHAIN,
                FLAG_HAS_EXTERNAL_KS, FLAG_HAS_INTERNAL_KS, FLAG_IDENTITY_HYBRID, FLAG_IDENTITY_STANDING};
        String[] names = {"HAS_CHAIN_EDGES", "MCP_USE_ALL_TOOLS", "GENERATIVE_ACTIONS",
                "AUTH_MODE_NONE", "ACCESS_POLICY_MULTI_TENANT", "ACCESS_POLICY_ANY",
                "HAS_ANONYMOUS_CRED", "HAS_MAKER_CRED", "CROSS_OWNER_CHAIN",
                "HAS_EXTERNAL_KS", "HAS_INTERNAL_KS", "IDENTITY_HYBRID", "IDENTITY_STANDING"};
        for (int i = 0; i < allFlags.length; i++) {
            dao.insertEntitlementValue(allFlags[i], AgentImportDao.ENT_TYPE_CAPABILITY, "FLAG::" + names[i]);
        }
    }

    private String determineKsKind(JsonNode comp) {
        String name = textOr(comp, "name", "").toLowerCase();
        String desc = textOr(comp, "description", "").toLowerCase();
        if (name.contains("bing custom search") || desc.contains("bing custom search")) return "BingCustomSearch";
        if (name.contains("sharepoint") || desc.contains("sharepoint")) return "SharePoint";
        if (desc.contains("dataverse")) return "Dataverse";
        // Default: if it has URLs, it's a public site
        JsonNode urls = comp.path("urls");
        if (urls.isArray() && !urls.isEmpty()) return "PublicSite";
        return "Unknown";
    }

    private String resolveActionCategory(String operationId) {
        if (operationId == null || operationId.isEmpty()) return "EXECUTE";
        String lower = operationId.toLowerCase();
        if (lower.startsWith("list") || lower.startsWith("get") || lower.startsWith("read") || lower.startsWith("search") || lower.startsWith("find")) return "READ";
        if (lower.startsWith("create") || lower.startsWith("add") || lower.startsWith("insert") || lower.startsWith("post")) return "CREATE";
        if (lower.startsWith("update") || lower.startsWith("edit") || lower.startsWith("modify") || lower.startsWith("patch") || lower.startsWith("set")) return "UPDATE";
        if (lower.startsWith("delete") || lower.startsWith("remove")) return "DELETE";
        if (lower.startsWith("approve") || lower.startsWith("confirm") || lower.startsWith("accept")) return "APPROVE";
        return "EXECUTE";
    }

    private void resetCounters() {
        nextAgentKey = AGENT_KEY_START;
        nextToolKey = TOOL_KEY_START;
        nextKsKey = KS_KEY_START;
        nextCredKey = CRED_KEY_START;
        nextUserKey = USER_KEY_START;
        nextAccountKey = ACCOUNT_KEY_START;
        agentNameToKey.clear();
        connectionIdToAgents.clear();
    }

    private static String textOr(JsonNode node, String field, String defaultVal) {
        JsonNode val = node.path(field);
        return val.isTextual() ? val.asText() : defaultVal;
    }

    record ParsedAgent(long agentKey, String name, String id, List<String> chainTargetNames, String ownerEntraId) {}
}
