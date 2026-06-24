package org.example.weflow.workflow.agent.runtime;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.invocation.InvocationContext;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.example.weflow.workflow.agent.AgentThreadState;

public record ToolCallContext(
        AgentRunContext runContext,
        AgentThreadState state,
        List<ToolExecutionRequest> toolRequests,
        // 工具调用上下文
        InvocationContext invocationContext,
        // 子Agent剩余时间
        Optional<Duration> remainingOverallTimeout
) {

    public ToolCallContext {
        Objects.requireNonNull(runContext, "runContext must not be null");
        Objects.requireNonNull(state, "state must not be null");
        toolRequests = List.copyOf(Objects.requireNonNull(toolRequests, "toolRequests must not be null"));
        Objects.requireNonNull(invocationContext, "invocationContext must not be null");
        remainingOverallTimeout = remainingOverallTimeout == null ? Optional.empty() : remainingOverallTimeout;
    }
}
