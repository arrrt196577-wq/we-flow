package org.example.weflow.core.agent;

public record AgentResult(
        String taskId,
        String agentCode,
        AgentStatus status,
        String output,
        String errorCode,
        String errorMessage
) {

    public static AgentResult success(String taskId, String agentCode, String output) {
        return new AgentResult(taskId, agentCode, AgentStatus.SUCCESS, output, null, null);
    }

    public static AgentResult failed(String taskId, String agentCode, String errorCode, String errorMessage) {
        return new AgentResult(taskId, agentCode, AgentStatus.FAILED, null, errorCode, errorMessage);
    }
}
