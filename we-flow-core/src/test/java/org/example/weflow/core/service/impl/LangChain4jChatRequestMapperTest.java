package org.example.weflow.core.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import java.util.List;
import org.example.weflow.core.service.dto.ChatHistoryMessage;
import org.example.weflow.core.service.dto.ChatStreamRequest;
import org.junit.jupiter.api.Test;

class LangChain4jChatRequestMapperTest {

    @Test
    void nullHistoryProducesOnlyCurrentUserMessage() {
        ChatStreamRequest request = new ChatStreamRequest("hello", null, null);

        ChatRequest chatRequest = LangChain4jChatRequestMapper.toChatRequest(request);

        assertThat(chatRequest.messages()).hasSize(1);
        ChatMessage message = chatRequest.messages().getFirst();
        assertThat(message).isInstanceOf(UserMessage.class);
        assertThat(((UserMessage) message).singleText()).isEqualTo("hello");
    }

    @Test
    void emptyHistoryProducesOnlyCurrentUserMessage() {
        ChatStreamRequest request = new ChatStreamRequest("hello", null, List.of());

        ChatRequest chatRequest = LangChain4jChatRequestMapper.toChatRequest(request);

        assertThat(chatRequest.messages()).hasSize(1);
        ChatMessage message = chatRequest.messages().getFirst();
        assertThat(message).isInstanceOf(UserMessage.class);
        assertThat(((UserMessage) message).singleText()).isEqualTo("hello");
    }

    @Test
    void historyKeepsOrderAndCurrentMessageIsAppendedLast() {
        ChatStreamRequest request = new ChatStreamRequest(
                "current",
                "conversation-1",
                List.of(
                        new ChatHistoryMessage("system", "system prompt"),
                        new ChatHistoryMessage("user", "previous user"),
                        new ChatHistoryMessage("assistant", "previous answer")
                )
        );

        ChatRequest chatRequest = LangChain4jChatRequestMapper.toChatRequest(request);

        assertThat(chatRequest.messages()).hasSize(4);
        assertThat(chatRequest.messages().get(0)).isInstanceOf(SystemMessage.class);
        assertThat(((SystemMessage) chatRequest.messages().get(0)).text()).isEqualTo("system prompt");
        assertThat(chatRequest.messages().get(1)).isInstanceOf(UserMessage.class);
        assertThat(((UserMessage) chatRequest.messages().get(1)).singleText()).isEqualTo("previous user");
        assertThat(chatRequest.messages().get(2)).isInstanceOf(AiMessage.class);
        assertThat(((AiMessage) chatRequest.messages().get(2)).text()).isEqualTo("previous answer");
        assertThat(chatRequest.messages().get(3)).isInstanceOf(UserMessage.class);
        assertThat(((UserMessage) chatRequest.messages().get(3)).singleText()).isEqualTo("current");
    }

    @Test
    void assistantAndAiRolesBothMapToAiMessage() {
        ChatStreamRequest request = new ChatStreamRequest(
                "current",
                null,
                List.of(
                        new ChatHistoryMessage("ASSISTANT", "assistant answer"),
                        new ChatHistoryMessage(" ai ", "ai answer")
                )
        );

        ChatRequest chatRequest = LangChain4jChatRequestMapper.toChatRequest(request);

        assertThat(chatRequest.messages().get(0)).isInstanceOf(AiMessage.class);
        assertThat(((AiMessage) chatRequest.messages().get(0)).text()).isEqualTo("assistant answer");
        assertThat(chatRequest.messages().get(1)).isInstanceOf(AiMessage.class);
        assertThat(((AiMessage) chatRequest.messages().get(1)).text()).isEqualTo("ai answer");
    }

    @Test
    void assistantReasoningContentMapsToAiMessageThinking() {
        ChatStreamRequest request = new ChatStreamRequest(
                "current",
                null,
                List.of(new ChatHistoryMessage("assistant", "previous answer", "previous reasoning"))
        );

        ChatRequest chatRequest = LangChain4jChatRequestMapper.toChatRequest(request);

        assertThat(chatRequest.messages().get(0)).isInstanceOf(AiMessage.class);
        AiMessage aiMessage = (AiMessage) chatRequest.messages().get(0);
        assertThat(aiMessage.text()).isEqualTo("previous answer");
        assertThat(aiMessage.thinking()).isEqualTo("previous reasoning");
    }

    @Test
    void unsupportedRoleThrowsIllegalArgumentException() {
        ChatStreamRequest request = new ChatStreamRequest(
                "current",
                null,
                List.of(new ChatHistoryMessage("tool", "tool result"))
        );

        assertThatThrownBy(() -> LangChain4jChatRequestMapper.toChatRequest(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported chat message role: tool");
    }
}
