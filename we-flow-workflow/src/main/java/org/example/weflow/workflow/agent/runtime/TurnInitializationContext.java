package org.example.weflow.workflow.agent.runtime;

import java.util.Objects;
import org.example.weflow.workflow.agent.AgentThreadState;

public record TurnInitializationContext(
        AgentRunContext runContext,
        AgentThreadState state
) {

    public TurnInitializationContext {
        Objects.requireNonNull(runContext, "runContext must not be null");
        Objects.requireNonNull(state, "state must not be null");
    }
}
