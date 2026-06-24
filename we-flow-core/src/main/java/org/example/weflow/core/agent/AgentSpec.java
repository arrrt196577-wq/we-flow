package org.example.weflow.core.agent;

import java.util.Objects;

public record AgentSpec(
        AgentDefinition definition,
        String systemPrompt,
        AgentToolPolicy toolPolicy,
        AgentRuntimeLimits runtimeLimits
) {

    public AgentSpec {
        Objects.requireNonNull(definition, "definition must not be null");
        systemPrompt = systemPrompt == null ? "" : systemPrompt;
        toolPolicy = toolPolicy == null ? AgentToolPolicy.all() : toolPolicy;
        Objects.requireNonNull(runtimeLimits, "runtimeLimits must not be null");
    }
}
