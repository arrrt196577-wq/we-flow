package org.example.weflow.api.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@Valid @RequestBody ChatRequest request) {
        throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "Streaming chat is not implemented yet.");
    }

    public record ChatRequest(
            @NotBlank String message,
            String conversationId,
            List<ChatMessage> history
    ) {
    }

    public record ChatMessage(
            @NotBlank String role,
            @NotBlank String content
    ) {
    }
}
