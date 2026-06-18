package org.example.weflow.core.agent;

public record AgentTask(
        String taskId,
        String taskType,
        String objective,
        String input
) {
}
