package org.example.weflow.core.service.dto;

public record ChatHistoryMessage(
        String role,
        String content,
        String reasoningContent
) {

    public ChatHistoryMessage(String role, String content) {
        this(role, content, null);
    }
}
