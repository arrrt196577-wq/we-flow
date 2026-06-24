package org.example.weflow.workflow.agent.runtime;

import dev.langchain4j.model.chat.request.ChatRequest;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;
import org.example.weflow.core.service.dto.ChatStreamChunk;
import org.example.weflow.workflow.agent.AgentThreadState;

public record ModelCallContext(
        AgentRunContext runContext,
        AgentThreadState state,
        ChatRequest request,
        Duration timeout,
        Consumer<ChatStreamChunk> partialSink
) {

    public ModelCallContext {
        Objects.requireNonNull(runContext, "runContext must not be null");
        Objects.requireNonNull(state, "state must not be null");
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(timeout, "timeout must not be null");
    }
}
