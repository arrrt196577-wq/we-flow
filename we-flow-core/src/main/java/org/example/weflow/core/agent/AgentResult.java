package org.example.weflow.core.agent;

import java.time.Instant;
import java.util.UUID;

public record AgentResult(
        String taskId,
        String traceId,
        String agentCode,
        AgentStatus status,
        String output,
        String errorCode,
        String errorMessage,
        Instant startedAt,
        Instant completedAt
) {

    public AgentResult {
        traceId = hasText(traceId) ? traceId : UUID.randomUUID().toString();
        startedAt = startedAt == null ? Instant.now() : startedAt;
        completedAt = completedAt == null ? startedAt : completedAt;
    }

    public static AgentResult success(
            String taskId,
            String traceId,
            String agentCode,
            String output,
            Instant startedAt,
            Instant completedAt
    ) {
        return new AgentResult(taskId, traceId, agentCode, AgentStatus.SUCCESS, output, null, null, startedAt, completedAt);
    }

    public static AgentResult success(String taskId, String agentCode, String output) {
        Instant now = Instant.now();
        return success(taskId, UUID.randomUUID().toString(), agentCode, output, now, now);
    }

    public static AgentResult failed(
            String taskId,
            String traceId,
            String agentCode,
            String errorCode,
            String errorMessage,
            Instant startedAt,
            Instant completedAt
    ) {
        return new AgentResult(taskId, traceId, agentCode, AgentStatus.FAILED, null, errorCode, errorMessage, startedAt, completedAt);
    }

    public static AgentResult failed(String taskId, String agentCode, String errorCode, String errorMessage) {
        Instant now = Instant.now();
        return failed(taskId, UUID.randomUUID().toString(), agentCode, errorCode, errorMessage, now, now);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
