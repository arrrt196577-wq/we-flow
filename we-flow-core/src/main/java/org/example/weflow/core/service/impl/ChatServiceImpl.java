package org.example.weflow.core.service.impl;

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import java.util.function.Consumer;
import org.example.weflow.core.service.IChatService;
import org.example.weflow.core.service.dto.ChatStreamRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "we-flow.chat", name = "engine", havingValue = "direct", matchIfMissing = true)
public class ChatServiceImpl implements IChatService {

    private final StreamingChatModel streamingChatModel;

    public ChatServiceImpl(StreamingChatModel streamingChatModel) {
        this.streamingChatModel = streamingChatModel;
    }

    @Override
    public void stream(ChatStreamRequest request, Consumer<String> onChunk, Consumer<Throwable> onError, Runnable onComplete) {
        ChatRequest chatRequest = LangChain4jChatRequestMapper.toChatRequest(request);
        streamingChatModel.chat(chatRequest, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                onChunk.accept(partialResponse);
            }

            @Override
            public void onCompleteResponse(ChatResponse response) {
                onComplete.run();
            }

            @Override
            public void onError(Throwable throwable) {
                onError.accept(throwable);
            }
        });
    }
}
