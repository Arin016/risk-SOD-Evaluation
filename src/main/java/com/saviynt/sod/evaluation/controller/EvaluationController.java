package com.saviynt.sod.evaluation.controller;

import com.saviynt.sod.evaluation.dto.EvaluationRequest;
import com.saviynt.sod.evaluation.dto.EvaluationResult;
import com.saviynt.sod.evaluation.service.EvaluationOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * REST API for triggering and monitoring SOD evaluation runs.
 */
@RestController
@RequestMapping("/api/v1")
public class EvaluationController {

    private static final Logger log = LoggerFactory.getLogger(EvaluationController.class);

    private final EvaluationOrchestrator orchestrator;
    private final ConcurrentHashMap<Long, EvaluationResult> completedJobs = new ConcurrentHashMap<>();
    private volatile EvaluationResult currentRun = null;

    public EvaluationController(EvaluationOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    /**
     * Trigger a detective SOD evaluation run.
     * Runs asynchronously — returns job ID immediately, poll /status/{jobId} for result.
     */
    @PostMapping("/evaluate")
    public ResponseEntity<?> triggerEvaluation(@RequestBody(required = false) EvaluationRequest request) {
        if (currentRun != null && "RUNNING".equals(currentRun.status())) {
            return ResponseEntity.status(409).body("Evaluation already in progress");
        }

        if (request == null) {
            request = new EvaluationRequest(List.of(), null, null, null);
        }

        EvaluationRequest finalRequest = request;
        CompletableFuture.runAsync(() -> {
            EvaluationResult result = orchestrator.evaluate(finalRequest);
            completedJobs.put(result.jobId(), result);
            currentRun = result;
        });

        return ResponseEntity.accepted().body(Map.of("message", "Evaluation started", "status", "ACCEPTED"));
    }

    /**
     * Run evaluation synchronously (for testing / small datasets).
     */
    @PostMapping("/evaluate/sync")
    public ResponseEntity<EvaluationResult> triggerEvaluationSync(@RequestBody(required = false) EvaluationRequest request) {
        if (request == null) {
            request = new EvaluationRequest(List.of(), null, null, null);
        }
        EvaluationResult result = orchestrator.evaluate(request);
        completedJobs.put(result.jobId(), result);
        return ResponseEntity.ok(result);
    }

    /**
     * Get the status/result of the last or current evaluation run.
     */
    @GetMapping("/status")
    public ResponseEntity<?> getStatus() {
        if (currentRun == null) {
            return ResponseEntity.ok(Map.of("status", "IDLE", "message", "No evaluation has been run"));
        }
        return ResponseEntity.ok(currentRun);
    }

    /**
     * Health check.
     */
    @GetMapping("/health")
    public ResponseEntity<?> health() {
        return ResponseEntity.ok(Map.of("status", "UP", "service", "sod-evaluation-service"));
    }

    // Need this import for the Map.of usage in responses
    private static final class Map {
        static java.util.Map<String, Object> of(String k1, Object v1, String k2, Object v2) {
            return java.util.Map.of(k1, v1, k2, v2);
        }
        static java.util.Map<String, Object> of(String k1, Object v1, String k2, Object v2, String k3, Object v3) {
            return java.util.Map.of(k1, v1, k2, v2, k3, v3);
        }
    }
}
