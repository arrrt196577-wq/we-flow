package org.example.weflow.workflow.chat;

import static org.assertj.core.api.Assertions.assertThat;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.example.weflow.core.service.IChatService;
import org.example.weflow.core.service.dto.ChatStreamRequest;
import org.example.weflow.core.service.impl.ChatServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class LangGraph4jChatHarnessServiceTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(StreamingChatModel.class, RecordingChatModel::new)
            .withUserConfiguration(
                    ChatServiceImpl.class,
                    LangGraph4jChatHarnessConfiguration.class,
                    LangGraph4jChatHarnessService.class
            );

    @Test
    void defaultConfigurationUsesDirectChatService() {
        contextRunner.run(context -> assertThat(context)
                .hasSingleBean(IChatService.class)
                .getBean(IChatService.class)
                .isInstanceOf(ChatServiceImpl.class));
    }

    @Test
    void langGraph4jEngineUsesHarnessService() {
        contextRunner
                .withPropertyValues("we-flow.chat.engine=langgraph4j")
                .run(context -> assertThat(context)
                        .hasSingleBean(IChatService.class)
                        .getBean(IChatService.class)
                        .isInstanceOf(LangGraph4jChatHarnessService.class));
    }

    @Test
    void sameConversationIdRestoresPreviousCheckpointMessages() {
        RecordingChatModel chatModel = new RecordingChatModel();
        LangGraph4jChatHarnessService service = service(chatModel);

        String first = stream(service, new ChatStreamRequest("my name is zhang san", "conversation-1", null));
        String second = stream(service, new ChatStreamRequest("what is my name?", "conversation-1", null));

        assertThat(first).isEqualTo("call-1 messages=user:my name is zhang san");
        assertThat(second).isEqualTo("call-2 messages=user:my name is zhang san|assistant:call-1 messages=user:my name is zhang san|user:what is my name?");
        assertThat(chatModel.requests()).hasSize(2);
        assertThat(chatModel.requests().get(1).messages()).hasSize(3);
    }

    @Test
    void differentConversationIdsDoNotShareCheckpointMessages() {
        RecordingChatModel chatModel = new RecordingChatModel();
        LangGraph4jChatHarnessService service = service(chatModel);

        stream(service, new ChatStreamRequest("my name is zhang san", "conversation-1", null));
        String isolated = stream(service, new ChatStreamRequest("what is my name?", "conversation-2", null));

        assertThat(isolated).isEqualTo("call-2 messages=user:what is my name?");
        assertThat(chatModel.requests().get(1).messages()).hasSize(1);
    }

    @Test
    void missingConversationIdReportsError() {
        LangGraph4jChatHarnessService service = service(new RecordingChatModel());
        AtomicReference<Throwable> error = new AtomicReference<>();

        service.stream(
                new ChatStreamRequest("hello", null, null),
                chunk -> {
                },
                error::set,
                () -> {
                }
        );

        assertThat(error.get())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("conversationId is required");
    }

    private LangGraph4jChatHarnessService service(StreamingChatModel chatModel) {
        return new LangGraph4jChatHarnessService(new ChatHarnessGraphFactory(chatModel));
    }

    private String stream(LangGraph4jChatHarnessService service, ChatStreamRequest request) {
        StringBuilder chunks = new StringBuilder();
        AtomicReference<Throwable> error = new AtomicReference<>();

        service.stream(request, chunks::append, error::set, () -> {
        });

        assertThat(error.get()).isNull();
        return chunks.toString();
    }

    private static final class RecordingChatModel implements StreamingChatModel {

        private final List<ChatRequest> requests = new ArrayList<>();

        @Override
        public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
            requests.add(request);
            String response = "call-" + requests.size() + " messages=" + messagesAsText(request.messages());
            handler.onPartialResponse(response);
            handler.onCompleteResponse(ChatResponse.builder()
                    .aiMessage(AiMessage.from(response))
                    .build());
        }

        List<ChatRequest> requests() {
            return requests;
        }

        private String messagesAsText(List<ChatMessage> messages) {
            return messages.stream()
                    .map(this::messageAsText)
                    .reduce((left, right) -> left + "|" + right)
                    .orElse("");
        }

        private String messageAsText(ChatMessage message) {
            if (message instanceof UserMessage userMessage) {
                return "user:" + userMessage.singleText();
            }
            if (message instanceof AiMessage aiMessage) {
                return "assistant:" + aiMessage.text();
            }
            return message.type().name().toLowerCase() + ":" + message.toString();
        }
    }
}
