package org.example.weflow.api.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record ChatRequest(
        @NotBlank String message,
        String conversationId,
        List<ChatMessage> history
) {
}
