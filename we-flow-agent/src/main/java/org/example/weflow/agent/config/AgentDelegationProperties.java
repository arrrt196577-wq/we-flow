package org.example.weflow.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "we-flow.agent.delegation")
public record AgentDelegationProperties(
        Boolean enabled
) {

    public AgentDelegationProperties {
        enabled = enabled != null && enabled;
    }

    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }
}
