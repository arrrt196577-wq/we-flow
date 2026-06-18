package org.example.weflow.workflow.chat;

import java.util.Map;
import java.util.function.Consumer;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.RunnableConfig;
import org.example.weflow.core.agent.AgentSpec;
import org.example.weflow.core.service.IChatService;
import org.example.weflow.core.service.dto.ChatStreamChunk;
import org.example.weflow.core.service.dto.ChatStreamRequest;
import org.example.weflow.workflow.agent.AgentGraphFactory;
import org.example.weflow.workflow.agent.AgentThreadState;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@ConditionalOnProperty(prefix = "we-flow.chat", name = "engine", havingValue = "langgraph4j")
public class LangGraph4jAgentChatService implements IChatService {

    private final CompiledGraph<AgentThreadState> graph;

    public LangGraph4jAgentChatService(
            AgentGraphFactory graphFactory,
            @Qualifier("leadAgentSpec") AgentSpec leadAgentSpec
    ) {
        this.graph = graphFactory.create(leadAgentSpec);
    }

    @Override
    public void stream(ChatStreamRequest request, Consumer<ChatStreamChunk> onChunk, Consumer<Throwable> onError, Runnable onComplete) {
        try {
            String conversationId = requiredConversationId(request);
            AgentThreadState finalState = graph.invoke(
                    Map.of(AgentThreadState.CURRENT_USER_MESSAGE, request.message()),
                    RunnableConfig.builder()
                            .threadId(conversationId)
                            .build()
            ).orElseThrow(() -> new IllegalStateException("LangGraph4j agent chat graph did not produce a final state."));

            if (StringUtils.hasText(finalState.currentAssistantThinking())) {
                onChunk.accept(ChatStreamChunk.reasoning(finalState.currentAssistantThinking()));
            }
            onChunk.accept(ChatStreamChunk.content(finalState.currentAssistantMessage()));
            onComplete.run();
        } catch (Throwable throwable) {
            onError.accept(throwable);
        }
    }

    private String requiredConversationId(ChatStreamRequest request) {
        if (!StringUtils.hasText(request.conversationId())) {
            throw new IllegalArgumentException("conversationId is required when we-flow.chat.engine=langgraph4j");
        }
        return request.conversationId();
    }
}
