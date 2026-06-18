package org.example.weflow.core.agent;

public record AgentContext(
        String parentAgentCode,
        String traceId
) {

    public AgentContext(String parentAgentCode) {
        this(parentAgentCode, null);
    }
}
