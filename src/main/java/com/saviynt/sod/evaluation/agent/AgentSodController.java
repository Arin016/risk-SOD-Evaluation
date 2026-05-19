package com.saviynt.sod.evaluation.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.saviynt.sod.evaluation.dto.EvaluationRequest;
import com.saviynt.sod.evaluation.dto.EvaluationResult;
import com.saviynt.sod.evaluation.service.EvaluationOrchestrator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST API for Agent SOD: import data, manage rules, trigger evaluation.
 */
@RestController
@RequestMapping("/api/v1/agent-sod")
public class AgentSodController {

    private final AgentImportService importService;
    private final AgentRuleDao ruleDao;
    private final EvaluationOrchestrator orchestrator;
    private final JdbcTemplate jdbc;

    public AgentSodController(AgentImportService importService, AgentRuleDao ruleDao,
                              EvaluationOrchestrator orchestrator, JdbcTemplate jdbc) {
        this.importService = importService;
        this.ruleDao = ruleDao;
        this.orchestrator = orchestrator;
        this.jdbc = jdbc;
    }

    // ─── Data Import ───────────────────────────────────────────────────────────

    @PostMapping("/import")
    public ResponseEntity<Map<String, Object>> importAgents(@RequestBody JsonNode body) {
        JsonNode agentsArray = body.path("agents");
        JsonNode credentials = body.path("credentials");
        List<JsonNode> agents = new java.util.ArrayList<>();
        if (agentsArray.isArray()) {
            for (JsonNode a : agentsArray) agents.add(a);
        }
        var result = importService.importAgents(agents, credentials.isMissingNode() ? null : credentials);
        return ResponseEntity.ok(result);
    }

    // ─── Rulesets ──────────────────────────────────────────────────────────────

    @PostMapping("/rulesets")
    public ResponseEntity<Map<String, Object>> createRuleset(@RequestBody Map<String, String> body) {
        long key = ruleDao.createRuleset(body.get("name"), body.getOrDefault("description", ""));
        return ResponseEntity.ok(Map.of("rulesetKey", key));
    }

    @GetMapping("/rulesets")
    public ResponseEntity<List<Map<String, Object>>> listRulesets() {
        return ResponseEntity.ok(ruleDao.listRulesets());
    }

    @PutMapping("/rulesets/{id}")
    public ResponseEntity<Map<String, Object>> updateRuleset(@PathVariable long id, @RequestBody Map<String, String> body) {
        ruleDao.updateRuleset(id, body.get("name"), body.getOrDefault("description", ""));
        return ResponseEntity.ok(Map.of("updated", true));
    }

    // ─── Functions ─────────────────────────────────────────────────────────────

    @PostMapping("/functions")
    public ResponseEntity<Map<String, Object>> createFunction(@RequestBody Map<String, Object> body) {
        String name = (String) body.get("name");
        String type = (String) body.getOrDefault("type", "SAP");
        long rulesetKey = ((Number) body.get("rulesetKey")).longValue();
        long key = ruleDao.createFunction(name, type, rulesetKey);
        return ResponseEntity.ok(Map.of("functionKey", key));
    }

    @GetMapping("/functions")
    public ResponseEntity<List<Map<String, Object>>> listFunctions(@RequestParam long rulesetKey) {
        return ResponseEntity.ok(ruleDao.listFunctions(rulesetKey));
    }

    @PutMapping("/functions/{id}")
    public ResponseEntity<Map<String, Object>> updateFunction(@PathVariable long id, @RequestBody Map<String, Object> body) {
        ruleDao.updateFunction(id, (String) body.get("name"), (String) body.get("type"));
        return ResponseEntity.ok(Map.of("updated", true));
    }

    @DeleteMapping("/functions/{id}")
    public ResponseEntity<Map<String, Object>> deleteFunction(@PathVariable long id) {
        ruleDao.deleteFunction(id);
        return ResponseEntity.ok(Map.of("deleted", true));
    }

    // ─── Conditions ────────────────────────────────────────────────────────────

    /**
     * Add a condition to a function.
     * For SAP: { "groupKey":1, "tcodeKey":300000000, "objectKey":9001, "fieldKey":1, "minValue":"X", "maxValue":"X" }
     * For NonSAP: { "entitlementKey":300400001, "position":1, "prevOperator":null, "nextOperator":"||" }
     */
    @PostMapping("/functions/{id}/conditions")
    public ResponseEntity<Map<String, Object>> addCondition(@PathVariable long id, @RequestBody Map<String, Object> body) {
        if (body.containsKey("objectKey")) {
            long groupKey = ((Number) body.getOrDefault("groupKey", 1)).longValue();
            long tcodeKey = ((Number) body.getOrDefault("tcodeKey", AgentImportDao.STAR_TCODE_KEY)).longValue();
            long objectKey = ((Number) body.get("objectKey")).longValue();
            long fieldKey = ((Number) body.getOrDefault("fieldKey", 1)).longValue();
            String minValue = (String) body.get("minValue");
            String maxValue = (String) body.getOrDefault("maxValue", minValue);
            long condId = ruleDao.addSapCondition(id, groupKey, tcodeKey, objectKey, fieldKey, minValue, maxValue);
            return ResponseEntity.ok(Map.of("conditionId", condId));
        } else {
            long entKey = ((Number) body.get("entitlementKey")).longValue();
            int position = ((Number) body.getOrDefault("position", 1)).intValue();
            String prevOp = (String) body.get("prevOperator");
            String nextOp = (String) body.get("nextOperator");
            ruleDao.addNonSapCondition(id, entKey, position, prevOp, nextOp);
            return ResponseEntity.ok(Map.of("added", true));
        }
    }

    @GetMapping("/functions/{id}/conditions")
    public ResponseEntity<List<Map<String, Object>>> listConditions(@PathVariable long id) {
        return ResponseEntity.ok(ruleDao.listConditions(id));
    }

    @DeleteMapping("/functions/{id}/conditions/{cid}")
    public ResponseEntity<Map<String, Object>> deleteCondition(@PathVariable long id, @PathVariable long cid,
                                                                @RequestParam(defaultValue = "SAP") String type) {
        ruleDao.deleteCondition(id, cid, type);
        return ResponseEntity.ok(Map.of("deleted", true));
    }

    // ─── Risks ─────────────────────────────────────────────────────────────────

    @PostMapping("/risks")
    public ResponseEntity<Map<String, Object>> createRisk(@RequestBody Map<String, Object> body) {
        String name = (String) body.get("name");
        long rulesetKey = ((Number) body.get("rulesetKey")).longValue();
        @SuppressWarnings("unchecked")
        List<Number> funcIds = (List<Number>) body.get("functionKeys");
        List<Long> functionKeys = funcIds.stream().map(Number::longValue).toList();
        long riskId = ruleDao.createRisk(name, rulesetKey, functionKeys);
        return ResponseEntity.ok(Map.of("riskId", riskId));
    }

    @GetMapping("/risks")
    public ResponseEntity<List<Map<String, Object>>> listRisks(@RequestParam long rulesetKey) {
        return ResponseEntity.ok(ruleDao.listRisks(rulesetKey));
    }

    @PutMapping("/risks/{id}")
    public ResponseEntity<Map<String, Object>> updateRisk(@PathVariable long id, @RequestBody Map<String, Object> body) {
        String name = (String) body.get("name");
        @SuppressWarnings("unchecked")
        List<Number> funcIds = (List<Number>) body.get("functionKeys");
        List<Long> functionKeys = funcIds.stream().map(Number::longValue).toList();
        ruleDao.updateRisk(id, name, functionKeys);
        return ResponseEntity.ok(Map.of("updated", true));
    }

    @DeleteMapping("/risks/{id}")
    public ResponseEntity<Map<String, Object>> deleteRisk(@PathVariable long id) {
        ruleDao.deleteRisk(id);
        return ResponseEntity.ok(Map.of("deleted", true));
    }

    // ─── Evaluation ────────────────────────────────────────────────────────────

    @PostMapping("/evaluate")
    public ResponseEntity<EvaluationResult> evaluate(@RequestBody(required = false) Map<String, Object> body) {
        long rulesetKey = 300;
        if (body != null && body.containsKey("rulesetKey")) {
            rulesetKey = ((Number) body.get("rulesetKey")).longValue();
        }
        var request = new EvaluationRequest(List.of(rulesetKey), AgentImportDao.SECURITY_SYSTEM_KEY, null, null);
        var result = orchestrator.evaluate(request);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/violations")
    public ResponseEntity<List<Map<String, Object>>> getViolations(@RequestParam(defaultValue = "203") long rulesetKey) {
        String sql = """
                SELECT s.SODKEY, s.USERIDENTIFIER, s.RISKKEY, r.RISKNAME, s.STATUS, u.USERNAME
                FROM sodrisks_new_job s
                JOIN risks r ON s.RISKKEY = r.RISKID
                LEFT JOIN users u ON s.USERIDENTIFIER = u.USERKEY
                WHERE r.RULESETKEY = ? AND s.STATUS IN (1, 2, 3)
                ORDER BY s.SODKEY DESC
                """;
        return ResponseEntity.ok(jdbc.queryForList(sql, rulesetKey));
    }
}
