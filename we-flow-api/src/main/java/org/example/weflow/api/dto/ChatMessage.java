package org.example.weflow.api.dto;

import jakarta.validation.constraints.NotBlank;

public record ChatMessage(
        @NotBlank String role,
        @NotBlank String content,
        String reasoningContent
) {

    public ChatMessage(String role, String content) {
        this(role, content, null);
    }
}
