package org.example.weflow.workflow.agent;

import static org.bsc.langgraph4j.GraphDefinition.END;
import static org.bsc.langgraph4j.GraphDefinition.START;
import static org.bsc.langgraph4j.action.AsyncCommandAction.command_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
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
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.Command;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.bsc.langgraph4j.langchain4j.serializer.std.LC4jStateSerializer;
import org.bsc.langgraph4j.langchain4j.tool.LC4jToolService;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.example.weflow.core.agent.AgentSpec;

@Slf4j
public class AgentGraphFactory {

    static final String TURN_INITIALIZATION_NODE = "turn_initialization_node";
    static final String MODEL_NODE = "model_node";
    static final String TOOL_NODE = "tool_node";

    private static final String USE_TOOLS = "use_tools";
    private static final String FINISH = "finish";
    private static final String CONTINUE = "continue";
    private static final String WEB_SEARCH_TOOL = "web_search";

    private final StreamingChatModel streamingChatModel;
    private final LC4jToolService toolService;

    public AgentGraphFactory(StreamingChatModel streamingChatModel, LC4jToolService toolService) {
        this.streamingChatModel = streamingChatModel;
        this.toolService = toolService;
    }

    public CompiledGraph<AgentThreadState> create(AgentSpec spec) {
        try {
            GraphRuntime runtime = new GraphRuntime(spec);
            StateGraph<AgentThreadState> graph = new StateGraph<>(
                    MessagesState.SCHEMA,
                    new LC4jStateSerializer<>(AgentThreadState::new)
            );

            graph.addNode(TURN_INITIALIZATION_NODE, node_async(runtime::initializeTurn))
                    .addNode(MODEL_NODE, node_async(runtime::modelNode))
                    .addNode(TOOL_NODE, node_async(runtime::toolNode))
                    .addEdge(START, TURN_INITIALIZATION_NODE)
                    .addEdge(TURN_INITIALIZATION_NODE, MODEL_NODE)
                    .addConditionalEdges(MODEL_NODE, command_async(runtime::routeAfterModel), Map.of(
                            USE_TOOLS, TOOL_NODE,
                            FINISH, END
                    ))
                    .addConditionalEdges(TOOL_NODE, command_async(runtime::routeAfterTool), Map.of(
                            CONTINUE, MODEL_NODE,
                            FINISH, END
                    ));

            return graph.compile(CompileConfig.builder()
                    .checkpointSaver(new MemorySaver())
                    .build());
        } catch (GraphStateException e) {
            throw new IllegalStateException("Failed to create LangGraph4j agent graph: "
                    + spec.definition().code(), e);
        }
    }

    private final class GraphRuntime {

        private final AgentSpec spec;

        private GraphRuntime(AgentSpec spec) {
            this.spec = spec;
        }

        private Map<String, Object> initializeTurn(AgentThreadState state) {
            return Map.of(
                    MessagesState.MESSAGES_STATE, List.of(UserMessage.from(state.currentUserMessage())),
                    AgentThreadState.CURRENT_ASSISTANT_THINKING, "",
                    AgentThreadState.TOOL_ITERATION_COUNT, 0
            );
        }

        private Map<String, Object> modelNode(AgentThreadState state) {
            log.info("[{} modelNode] messages count: {}", spec.definition().code(), state.messages().size());
            ChatRequest chatRequest = ChatRequest.builder()
                    .messages(messagesForModel(state.messages()))
                    .toolSpecifications(availableToolSpecifications())
                    .build();
            AiMessage aiMessage = chat(chatRequest);
            return Map.of(
                    MessagesState.MESSAGES_STATE, List.of(aiMessage),
                    AgentThreadState.CURRENT_ASSISTANT_MESSAGE, assistantText(aiMessage),
                    AgentThreadState.CURRENT_ASSISTANT_THINKING, assistantThinking(aiMessage)
            );
        }

        private Command routeAfterModel(AgentThreadState state, RunnableConfig config) {
            return lastAiMessage(state)
                    .filter(AiMessage::hasToolExecutionRequests)
                    .map(aiMessage -> {
                        List<ToolExecutionRequest> unsupportedToolRequests =
                                unsupportedToolRequests(aiMessage.toolExecutionRequests());
                        if (!unsupportedToolRequests.isEmpty()) {
                            return finishCommand(unsupportedToolMessage(unsupportedToolRequests));
                        }
                        if (state.toolIterationCount() >= spec.maxToolIterations()) {
                            return finishCommand(maxToolIterationsMessage());
                        }
                        return new Command(USE_TOOLS);
                    })
                    .orElseGet(() -> new Command(FINISH));
        }

        private Command routeAfterTool(AgentThreadState state, RunnableConfig config) {
            return searchFailureMessage(state.messages())
                    .map(this::finishCommand)
                    .orElseGet(() -> new Command(CONTINUE));
        }

        private Map<String, Object> toolNode(AgentThreadState state) {
            AiMessage aiMessage = lastAiMessage(state)
                    .filter(AiMessage::hasToolExecutionRequests)
                    .orElseThrow(() -> new IllegalStateException("Tool node requires an AI message with tool requests."));
            List<ToolExecutionRequest> toolRequests = aiMessage.toolExecutionRequests();
            Command command = toolService.execute(toolRequests, invocationContext(state), MessagesState.MESSAGES_STATE).join();
            return Map.of(
                    MessagesState.MESSAGES_STATE, toolResultMessages(command),
                    AgentThreadState.TOOL_ITERATION_COUNT, state.toolIterationCount() + 1
            );
        }

        private List<ChatMessage> messagesForModel(List<ChatMessage> messages) {
            List<ChatMessage> modelMessages = new ArrayList<>(messages.size() + 1);
            if (!spec.systemPrompt().isBlank()) {
                modelMessages.add(SystemMessage.from(spec.systemPrompt()));
            }
            modelMessages.addAll(messages);
            return modelMessages;
        }

        private List<ToolSpecification> availableToolSpecifications() {
            return toolService.toolSpecifications().stream()
                    .filter(specification -> spec.toolPolicy().allows(specification.name()))
                    .toList();
        }

        private Set<String> availableToolNames() {
            return availableToolSpecifications().stream()
                    .map(ToolSpecification::name)
                    .collect(Collectors.toUnmodifiableSet());
        }

        private java.util.Optional<AiMessage> lastAiMessage(AgentThreadState state) {
            List<ChatMessage> messages = state.messages();
            for (int i = messages.size() - 1; i >= 0; i--) {
                if (messages.get(i) instanceof AiMessage aiMessage) {
                    return java.util.Optional.of(aiMessage);
                }
            }
            return java.util.Optional.empty();
        }

        private InvocationContext invocationContext(AgentThreadState state) {
            return InvocationContext.builder()
                    .invocationId(UUID.randomUUID())
                    .interfaceName(AgentGraphFactory.class.getSimpleName())
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

        private String assistantThinking(AiMessage aiMessage) {
            return aiMessage.thinking() == null ? "" : aiMessage.thinking();
        }

        private String maxToolIterationsMessage() {
            return "Tool execution stopped because the maximum number of tool iterations was reached.";
        }

        private List<ToolExecutionRequest> unsupportedToolRequests(List<ToolExecutionRequest> toolRequests) {
            Set<String> availableToolNames = availableToolNames();
            return toolRequests.stream()
                    .filter(toolRequest -> !availableToolNames.contains(toolRequest.name()))
                    .toList();
        }

        private String unsupportedToolMessage(List<ToolExecutionRequest> unsupportedToolRequests) {
            boolean includesWebSearch = unsupportedToolRequests.stream()
                    .anyMatch(toolRequest -> WEB_SEARCH_TOOL.equals(toolRequest.name()));
            if (includesWebSearch) {
                return "搜索功能未启用，当前无法联网查询。请启用 we-flow.search.enabled=true 后重试。";
            }

            String toolNames = unsupportedToolRequests.stream()
                    .map(ToolExecutionRequest::name)
                    .distinct()
                    .collect(Collectors.joining(", "));
            return "请求的工具不可用：" + toolNames + "，无法执行该操作。";
        }

        private java.util.Optional<String> searchFailureMessage(List<ChatMessage> messages) {
            return latestToolResultMessages(messages).stream()
                    .filter(ToolExecutionResultMessage.class::isInstance)
                    .map(ToolExecutionResultMessage.class::cast)
                    .filter(message -> WEB_SEARCH_TOOL.equals(message.toolName()))
                    .map(ToolExecutionResultMessage::text)
                    .filter(this::isToolError)
                    .map(this::searchFailureUserMessage)
                    .findFirst();
        }

        private List<ChatMessage> latestToolResultMessages(List<ChatMessage> messages) {
            int latestAiMessageIndex = -1;
            for (int i = messages.size() - 1; i >= 0; i--) {
                if (messages.get(i) instanceof AiMessage) {
                    latestAiMessageIndex = i;
                    break;
                }
            }
            if (latestAiMessageIndex < 0 || latestAiMessageIndex + 1 >= messages.size()) {
                return List.of();
            }
            return messages.subList(latestAiMessageIndex + 1, messages.size());
        }

        private boolean isToolError(String text) {
            return text != null && text.lines()
                    .map(String::trim)
                    .anyMatch(line -> "status: error".equalsIgnoreCase(line));
        }

        private String searchFailureUserMessage(String toolResultText) {
            String message = extractToolErrorMessage(toolResultText)
                    .orElse("搜索工具返回错误。");
            return "联网搜索失败：" + message + "。我没有获取到可靠搜索结果，因此不会继续生成联网结论。";
        }

        private java.util.Optional<String> extractToolErrorMessage(String toolResultText) {
            if (toolResultText == null) {
                return java.util.Optional.empty();
            }
            return toolResultText.lines()
                    .map(String::trim)
                    .filter(line -> line.startsWith("message:"))
                    .map(line -> line.substring("message:".length()).trim())
                    .filter(message -> !message.isBlank())
                    .findFirst();
        }

        private Command finishCommand(String message) {
            return new Command(FINISH, Map.of(
                    AgentThreadState.CURRENT_ASSISTANT_MESSAGE, message,
                    AgentThreadState.CURRENT_ASSISTANT_THINKING, "",
                    MessagesState.MESSAGES_STATE, List.of(AiMessage.from(message))
            ));
        }
    }

    private AiMessage chat(ChatRequest chatRequest) {
        CompletableFuture<AiMessage> responseFuture = new CompletableFuture<>();
        StringBuilder responseBuilder = new StringBuilder();
        StringBuilder thinkingBuilder = new StringBuilder();

        streamingChatModel.chat(chatRequest, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                responseBuilder.append(partialResponse);
            }

            @Override
            public void onPartialThinking(PartialThinking partialThinking) {
                thinkingBuilder.append(partialThinking.text());
            }

            @Override
            public void onCompleteResponse(ChatResponse response) {
                AiMessage aiMessage = response.aiMessage();
                if (aiMessage == null) {
                    responseFuture.complete(aiMessage(responseBuilder.toString(), thinkingBuilder.toString()));
                    return;
                }
                if (!aiMessage.hasToolExecutionRequests()
                        && (aiMessage.text() == null || aiMessage.text().isBlank())
                        && !responseBuilder.isEmpty()) {
                    responseFuture.complete(aiMessage(responseBuilder.toString(), thinkingBuilder.toString()));
                    return;
                }
                responseFuture.complete(withThinkingFallback(aiMessage, thinkingBuilder.toString()));
            }

            @Override
            public void onError(Throwable throwable) {
                responseFuture.completeExceptionally(throwable);
            }
        });

        return responseFuture.join();
    }

    private AiMessage aiMessage(String text, String thinking) {
        if (thinking == null || thinking.isBlank()) {
            return AiMessage.from(text);
        }
        return AiMessage.builder()
                .text(text)
                .thinking(thinking)
                .build();
    }

    private AiMessage withThinkingFallback(AiMessage aiMessage, String thinking) {
        if (aiMessage.thinking() != null && !aiMessage.thinking().isBlank()) {
            return aiMessage;
        }
        if (thinking == null || thinking.isBlank()) {
            return aiMessage;
        }
        return aiMessage.toBuilder()
                .thinking(thinking)
                .build();
    }
}
