package org.example.weflow.core.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.example.weflow.core.service.dto.ChatStreamChunk;
import org.example.weflow.core.service.dto.ChatStreamRequest;
import org.junit.jupiter.api.Test;

class ChatServiceImplTest {

    @Test
    void emitsReasoningAndContentChunks() {
        ChatServiceImpl service = new ChatServiceImpl(new ThinkingChatModel());
        List<ChatStreamChunk> chunks = new ArrayList<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicBoolean completed = new AtomicBoolean();

        service.stream(
                new ChatStreamRequest("hello", null, null),
                chunks::add,
                error::set,
                () -> completed.set(true)
        );

        assertThat(error.get()).isNull();
        assertThat(completed.get()).isTrue();
        assertThat(chunks).extracting(ChatStreamChunk::type)
                .containsExactly(ChatStreamChunk.Type.REASONING, ChatStreamChunk.Type.CONTENT);
        assertThat(chunks).extracting(ChatStreamChunk::content)
                .containsExactly("thinking...", "answer");
    }

    private static final class ThinkingChatModel implements StreamingChatModel {

        @Override
        public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
            handler.onPartialThinking(new PartialThinking("thinking..."));
            handler.onPartialResponse("answer");
            handler.onCompleteResponse(ChatResponse.builder()
                    .aiMessage(AiMessage.builder()
                            .thinking("thinking...")
                            .text("answer")
                            .build())
                    .build());
        }
    }
}
