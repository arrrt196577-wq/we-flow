package org.example.weflow.api.controller;

import jakarta.validation.Valid;
import java.io.IOException;
import org.example.weflow.api.dto.ChatRequest;
import org.example.weflow.core.service.IChatService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final IChatService chatService;

    public ChatController(IChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@Valid @RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(0L);

        chatService.stream(
                request.message(),
                chunk -> sendChunk(emitter, chunk),
                emitter::completeWithError,
                emitter::complete
        );

        return emitter;
    }

    private void sendChunk(SseEmitter emitter, String chunk) {
        try {
            emitter.send(SseEmitter.event().name("chunk").data(chunk));
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }
}
