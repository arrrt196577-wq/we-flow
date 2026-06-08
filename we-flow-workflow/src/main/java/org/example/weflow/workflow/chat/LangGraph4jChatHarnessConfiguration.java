package org.example.weflow.workflow.chat;

import dev.langchain4j.model.chat.StreamingChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(prefix = "we-flow.chat", name = "engine", havingValue = "langgraph4j")
class LangGraph4jChatHarnessConfiguration {

    @Bean
    ChatHarnessGraphFactory chatHarnessGraphFactory(StreamingChatModel streamingChatModel) {
        return new ChatHarnessGraphFactory(streamingChatModel);
    }
}
