package org.example.weflow.workflow.chat;

import static org.bsc.langgraph4j.GraphDefinition.END;
import static org.bsc.langgraph4j.GraphDefinition.START;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.bsc.langgraph4j.prebuilt.MessagesState;

final class ChatHarnessGraphFactory {

    static final String BEFORE_MODEL_HOOK = "before_model_hook";
    static final String MODEL_NODE = "model_node";
    static final String AFTER_MODEL_HOOK = "after_model_hook";

    private final StreamingChatModel streamingChatModel;

    ChatHarnessGraphFactory(StreamingChatModel streamingChatModel) {
        this.streamingChatModel = streamingChatModel;
    }

    CompiledGraph<ChatHarnessState> create() {
        try {
            StateGraph<ChatHarnessState> graph = new StateGraph<>(MessagesState.SCHEMA, ChatHarnessState::new);

            graph.addNode(BEFORE_MODEL_HOOK, node_async(this::beforeModelHook))
                    .addNode(MODEL_NODE, node_async(this::modelNode))
                    .addNode(AFTER_MODEL_HOOK, node_async(this::afterModelHook))
                    .addEdge(START, BEFORE_MODEL_HOOK)
                    .addEdge(BEFORE_MODEL_HOOK, MODEL_NODE)
                    .addEdge(MODEL_NODE, AFTER_MODEL_HOOK)
                    .addEdge(AFTER_MODEL_HOOK, END);

            return graph.compile(CompileConfig.builder()
                    .checkpointSaver(new MemorySaver())
                    .build());
        } catch (GraphStateException e) {
            throw new IllegalStateException("Failed to create LangGraph4j chat harness graph.", e);
        }
    }

    private Map<String, Object> beforeModelHook(ChatHarnessState state) {
        return Map.of(MessagesState.MESSAGES_STATE, List.of(new ChatHarnessMessage("user", state.currentUserMessage())));
    }

    private Map<String, Object> modelNode(ChatHarnessState state) {
        ChatRequest chatRequest = ChatRequest.builder()
                .messages(state.messages().stream()
                        .map(this::toLangChain4jMessage)
                        .toList())
                .build();
        String assistantMessage = chat(chatRequest);
        return Map.of(ChatHarnessState.CURRENT_ASSISTANT_MESSAGE, assistantMessage);
    }

    private Map<String, Object> afterModelHook(ChatHarnessState state) {
        return Map.of(MessagesState.MESSAGES_STATE, List.of(new ChatHarnessMessage("assistant", state.currentAssistantMessage())));
    }

    private dev.langchain4j.data.message.ChatMessage toLangChain4jMessage(ChatHarnessMessage message) {
        return switch (message.role()) {
            case "user" -> UserMessage.from(message.content());
            case "assistant" -> AiMessage.from(message.content());
            default -> throw new IllegalArgumentException("Unsupported chat harness message role: " + message.role());
        };
    }

    private String chat(ChatRequest chatRequest) {
        CompletableFuture<String> responseFuture = new CompletableFuture<>();
        StringBuilder responseBuilder = new StringBuilder();

        streamingChatModel.chat(chatRequest, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                responseBuilder.append(partialResponse);
            }

            @Override
            public void onCompleteResponse(ChatResponse response) {
                responseFuture.complete(responseBuilder.toString());
            }

            @Override
            public void onError(Throwable throwable) {
                responseFuture.completeExceptionally(throwable);
            }
        });

        return responseFuture.join();
    }
}
