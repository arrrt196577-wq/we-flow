package org.example.weflow.workflow.agent;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import org.example.weflow.core.service.dto.ChatStreamChunk;
import org.springframework.util.StringUtils;

public class AgentStreamSinkRegistry {

    private final ConcurrentMap<String, Consumer<ChatStreamChunk>> sinks = new ConcurrentHashMap<>();

    public void register(String threadId, Consumer<ChatStreamChunk> sink) {
        if (!StringUtils.hasText(threadId)) {
            throw new IllegalArgumentException("threadId must not be blank");
        }
        if (sink == null) {
            throw new IllegalArgumentException("sink must not be null");
        }
        sinks.put(threadId, sink);
    }

    public Optional<Consumer<ChatStreamChunk>> sink(String threadId) {
        if (!StringUtils.hasText(threadId)) {
            return Optional.empty();
        }
        return Optional.ofNullable(sinks.get(threadId));
    }

    public void unregister(String threadId) {
        if (StringUtils.hasText(threadId)) {
            sinks.remove(threadId);
        }
    }
}
