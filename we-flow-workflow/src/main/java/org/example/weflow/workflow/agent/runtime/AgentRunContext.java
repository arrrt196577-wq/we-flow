package org.example.weflow.workflow.agent.runtime;

import java.util.Objects;
import java.util.Optional;
import org.example.weflow.core.agent.AgentSpec;

public record AgentRunContext(
        AgentSpec spec,
        String threadId
) {

    public AgentRunContext {
        Objects.requireNonNull(spec, "spec must not be null");
        threadId = threadId == null ? "" : threadId;
    }

    public Optional<String> threadIdOptional() {
        return threadId.isBlank() ? Optional.empty() : Optional.of(threadId);
    }
}
