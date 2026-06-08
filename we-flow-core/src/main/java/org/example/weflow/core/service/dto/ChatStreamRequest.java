package org.example.weflow.core.service.dto;

import java.util.List;

public record ChatStreamRequest(
        String message,
        String conversationId,
        List<ChatHistoryMessage> history
) {

    public ChatStreamRequest {
        history = history == null ? List.of() : List.copyOf(history);
    }
}
