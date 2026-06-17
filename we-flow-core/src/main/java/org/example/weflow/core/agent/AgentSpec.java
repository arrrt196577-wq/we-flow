package org.example.weflow.core.agent;

import java.util.Objects;

public record AgentSpec(
        AgentDefinition definition,
        String systemPrompt,
        AgentToolPolicy toolPolicy,
        int maxToolIterations
) {

    public AgentSpec {
        Objects.requireNonNull(definition, "definition must not be null");
        systemPrompt = systemPrompt == null ? "" : systemPrompt;
        toolPolicy = toolPolicy == null ? AgentToolPolicy.all() : toolPolicy;
        if (maxToolIterations < 1) {
            throw new IllegalArgumentException("maxToolIterations must be positive");
        }
    }
}
