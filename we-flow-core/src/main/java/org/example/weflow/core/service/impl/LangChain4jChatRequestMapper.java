package org.example.weflow.core.service.impl;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.example.weflow.core.service.dto.ChatHistoryMessage;
import org.example.weflow.core.service.dto.ChatStreamRequest;

final class LangChain4jChatRequestMapper {

    private LangChain4jChatRequestMapper() {
    }

    static ChatRequest toChatRequest(ChatStreamRequest request) {
        List<ChatMessage> messages = new ArrayList<>();
        for (ChatHistoryMessage historyMessage : request.history()) {
            messages.add(toChatMessage(historyMessage));
        }
        messages.add(UserMessage.from(request.message()));

        return ChatRequest.builder()
                .messages(messages)
                .build();
    }

    private static ChatMessage toChatMessage(ChatHistoryMessage message) {
        String role = normalizeRole(message.role());
        return switch (role) {
            case "system" -> SystemMessage.from(message.content());
            case "user" -> UserMessage.from(message.content());
            case "assistant", "ai" -> toAiMessage(message);
            default -> throw new IllegalArgumentException("Unsupported chat message role: " + message.role());
        };
    }

    private static AiMessage toAiMessage(ChatHistoryMessage message) {
        if (!hasText(message.reasoningContent())) {
            return AiMessage.from(message.content());
        }
        return AiMessage.builder()
                .text(message.content())
                .thinking(message.reasoningContent())
                .build();
    }

    private static String normalizeRole(String role) {
        if (role == null) {
            throw new IllegalArgumentException("Unsupported chat message role: null");
        }
        return role.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
