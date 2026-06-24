package org.example.weflow.workflow.agent.runtime;

import java.util.Objects;
import org.example.weflow.workflow.agent.AgentThreadState;

public record FinishContext(
        AgentRunContext runContext,
        AgentThreadState state,
        String output
) {

    public FinishContext {
        Objects.requireNonNull(runContext, "runContext must not be null");
        Objects.requireNonNull(state, "state must not be null");
        output = output == null ? "" : output;
    }
}
