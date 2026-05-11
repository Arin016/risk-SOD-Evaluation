package com.saviynt.sod.evaluation.dto;

import java.time.Duration;
import java.time.Instant;

/**
 * Result summary of a completed SOD evaluation run.
 */
public record EvaluationResult(
        long jobId,
        String status,
        int totalAccounts,
        int totalUsers,
        int functionsEvaluated,
        int risksEvaluated,
        int violationsOpened,
        int violationsClosed,
        int violationsAccepted,
        Instant startTime,
        Instant endTime,
        Duration duration,
        String error
) {
    public static EvaluationResult success(long jobId, int accounts, int users, int functions,
                                           int risks, int opened, int closed, int accepted,
                                           Instant start, Instant end) {
        return new EvaluationResult(jobId, "SUCCESS", accounts, users, functions, risks,
                opened, closed, accepted, start, end, Duration.between(start, end), null);
    }

    public static EvaluationResult failure(long jobId, Instant start, String error) {
        return new EvaluationResult(jobId, "FAILURE", 0, 0, 0, 0, 0, 0, 0,
                start, Instant.now(), Duration.between(start, Instant.now()), error);
    }
}
