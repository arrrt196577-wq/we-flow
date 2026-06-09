package org.example.weflow.workflow.chat;

import static org.bsc.langgraph4j.GraphDefinition.END;
import static org.bsc.langgraph4j.GraphDefinition.START;
import static org.bsc.langgraph4j.action.AsyncCommandAction.command_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.Command;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.bsc.langgraph4j.langchain4j.serializer.std.LC4jStateSerializer;
import org.bsc.langgraph4j.langchain4j.tool.LC4jToolService;
import org.bsc.langgraph4j.prebuilt.MessagesState;

final class ChatHarnessGraphFactory {

    static final String BEFORE_MODEL_HOOK = "before_model_hook";
    static final String MODEL_NODE = "model_node";
    static final String TOOL_NODE = "tool_node";

    private static final String USE_TOOLS = "use_tools";
    private static final String FINISH = "finish";
    private static final int MAX_TOOL_ITERATIONS = 8;
    private static final String TOOL_SYSTEM_PROMPT = """
            You may use these workspace file tools when needed: find_files, list_dir, read_file.
            If the user references an uncertain path or file name, call find_files or list_dir before read_file.
            When read_file returns hasMore: true and you still need more content, call read_file again with nextStartLine.
            """;

    private final StreamingChatModel streamingChatModel;
    private final LC4jToolService toolService;

    ChatHarnessGraphFactory(StreamingChatModel streamingChatModel, LC4jToolService toolService) {
        this.streamingChatModel = streamingChatModel;
        this.toolService = toolService;
    }

    CompiledGraph<ChatHarnessState> create() {
        try {
            StateGraph<ChatHarnessState> graph = new StateGraph<>(
                    MessagesState.SCHEMA,
                    new LC4jStateSerializer<>(ChatHarnessState::new)
            );

            graph.addNode(BEFORE_MODEL_HOOK, node_async(this::beforeModelHook))
                    .addNode(MODEL_NODE, node_async(this::modelNode))
                    .addNode(TOOL_NODE, node_async(this::toolNode))
                    .addEdge(START, BEFORE_MODEL_HOOK)
                    .addEdge(BEFORE_MODEL_HOOK, MODEL_NODE)
                    .addConditionalEdges(MODEL_NODE, command_async(this::routeAfterModel), Map.of(
                            USE_TOOLS, TOOL_NODE,
                            FINISH, END
                    ))
                    .addEdge(TOOL_NODE, MODEL_NODE);

            return graph.compile(CompileConfig.builder()
                    .checkpointSaver(new MemorySaver())
                    .build());
        } catch (GraphStateException e) {
            throw new IllegalStateException("Failed to create LangGraph4j chat harness graph.", e);
        }
    }

    private Map<String, Object> beforeModelHook(ChatHarnessState state) {
        return Map.of(
                MessagesState.MESSAGES_STATE, List.of(UserMessage.from(state.currentUserMessage())),
                ChatHarnessState.TOOL_ITERATION_COUNT, 0
        );
    }

    private Map<String, Object> modelNode(ChatHarnessState state) {
        ChatRequest chatRequest = ChatRequest.builder()
                .messages(messagesForModel(state.messages()))
                .toolSpecifications(toolService.toolSpecifications())
                .build();
        AiMessage aiMessage = chat(chatRequest);
        return Map.of(
                MessagesState.MESSAGES_STATE, List.of(aiMessage),
                ChatHarnessState.CURRENT_ASSISTANT_MESSAGE, assistantText(aiMessage)
        );
    }

    private Command routeAfterModel(ChatHarnessState state, RunnableConfig config) {
        return lastAiMessage(state)
                .filter(AiMessage::hasToolExecutionRequests)
                .map(aiMessage -> {
                    if (state.toolIterationCount() >= MAX_TOOL_ITERATIONS) {
                        return new Command(FINISH, Map.of(
                                ChatHarnessState.CURRENT_ASSISTANT_MESSAGE, maxToolIterationsMessage(),
                                MessagesState.MESSAGES_STATE, List.of(AiMessage.from(maxToolIterationsMessage()))
                        ));
                    }
                    return new Command(USE_TOOLS);
                })
                .orElseGet(() -> new Command(FINISH));
    }

    private Map<String, Object> toolNode(ChatHarnessState state) {
        AiMessage aiMessage = lastAiMessage(state)
                .filter(AiMessage::hasToolExecutionRequests)
                .orElseThrow(() -> new IllegalStateException("Tool node requires an AI message with tool requests."));
        List<ToolExecutionRequest> toolRequests = aiMessage.toolExecutionRequests();
        Command command = toolService.execute(toolRequests, invocationContext(state), MessagesState.MESSAGES_STATE).join();
        return Map.of(
                MessagesState.MESSAGES_STATE, toolResultMessages(command),
                ChatHarnessState.TOOL_ITERATION_COUNT, state.toolIterationCount() + 1
        );
    }

    private List<ChatMessage> messagesForModel(List<ChatMessage> messages) {
        List<ChatMessage> modelMessages = new ArrayList<>(messages.size() + 1);
        modelMessages.add(SystemMessage.from(TOOL_SYSTEM_PROMPT));
        modelMessages.addAll(messages);
        return modelMessages;
    }

    private java.util.Optional<AiMessage> lastAiMessage(ChatHarnessState state) {
        List<ChatMessage> messages = state.messages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof AiMessage aiMessage) {
                return java.util.Optional.of(aiMessage);
            }
        }
        return java.util.Optional.empty();
    }

    private InvocationContext invocationContext(ChatHarnessState state) {
        return InvocationContext.builder()
                .invocationId(UUID.randomUUID())
                .interfaceName(ChatHarnessGraphFactory.class.getSimpleName())
                .methodName("toolNode")
                .methodArguments(List.of(state.currentUserMessage()))
                .chatMemoryId(state.value("thread_id").orElse(null))
                .invocationParameters(InvocationParameters.from(Map.of()))
                .timestamp(Instant.now())
                .build();
    }

    @SuppressWarnings("unchecked")
    private List<ToolExecutionResultMessage> toolResultMessages(Command command) {
        Object update = command.update().get(MessagesState.MESSAGES_STATE);
        if (update instanceof List<?> messages) {
            return (List<ToolExecutionResultMessage>) messages;
        }
        return List.of();
    }

    private String assistantText(AiMessage aiMessage) {
        return aiMessage.text() == null ? "" : aiMessage.text();
    }

    private String maxToolIterationsMessage() {
        return "Tool execution stopped because the maximum number of tool iterations was reached.";
    }

    private AiMessage chat(ChatRequest chatRequest) {
        CompletableFuture<AiMessage> responseFuture = new CompletableFuture<>();
        StringBuilder responseBuilder = new StringBuilder();

        streamingChatModel.chat(chatRequest, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                responseBuilder.append(partialResponse);
            }

            @Override
            public void onCompleteResponse(ChatResponse response) {
                AiMessage aiMessage = response.aiMessage();
                if (aiMessage == null) {
                    responseFuture.complete(AiMessage.from(responseBuilder.toString()));
                    return;
                }
                if (!aiMessage.hasToolExecutionRequests()
                        && (aiMessage.text() == null || aiMessage.text().isBlank())
                        && !responseBuilder.isEmpty()) {
                    responseFuture.complete(AiMessage.from(responseBuilder.toString()));
                    return;
                }
                responseFuture.complete(aiMessage);
            }

            @Override
            public void onError(Throwable throwable) {
                responseFuture.completeExceptionally(throwable);
            }
        });

        return responseFuture.join();
    }
}
