package org.example.weflow.core.agent;

public record AgentDefinition(
        String code,
        String name,
        AgentType type,
        String description,
        boolean enabled
) {
}
