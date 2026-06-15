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

@Slf4j
final class ChatHarnessGraphFactory {

    static final String BEFORE_MODEL_HOOK = "before_model_hook";
    static final String MODEL_NODE = "model_node";
    static final String TOOL_NODE = "tool_node";

    private static final String USE_TOOLS = "use_tools";
    private static final String FINISH = "finish";
    private static final String CONTINUE = "continue";
    private static final int MAX_TOOL_ITERATIONS = 8;
    private static final String WEB_SEARCH_TOOL = "web_search";
    private static final String DELEGATE_TASK_TOOL = "delegate_task";

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
                    .addConditionalEdges(TOOL_NODE, command_async(this::routeAfterTool), Map.of(
                            CONTINUE, MODEL_NODE,
                            FINISH, END
                    ));

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
                ChatHarnessState.CURRENT_ASSISTANT_THINKING, "",
                ChatHarnessState.TOOL_ITERATION_COUNT, 0
        );
    }

    private Map<String, Object> modelNode(ChatHarnessState state) {
        log.info("[modelNode] messages count: {}", state.messages().size());
        for (int i = 0; i < state.messages().size(); i++) {
            ChatMessage msg = state.messages().get(i);
            log.info("[modelNode] message[{}]: type={}, content={}", i, msg.type(), msg);
        }
        log.info("=========================================================");
        ChatRequest chatRequest = ChatRequest.builder()
                .messages(messagesForModel(state.messages()))
                .toolSpecifications(toolService.toolSpecifications())
                .build();
        AiMessage aiMessage = chat(chatRequest);
        return Map.of(
                MessagesState.MESSAGES_STATE, List.of(aiMessage),
                ChatHarnessState.CURRENT_ASSISTANT_MESSAGE, assistantText(aiMessage),
                ChatHarnessState.CURRENT_ASSISTANT_THINKING, assistantThinking(aiMessage)
        );
    }

    private Command routeAfterModel(ChatHarnessState state, RunnableConfig config) {
        return lastAiMessage(state)
                .filter(AiMessage::hasToolExecutionRequests)
                .map(aiMessage -> {
                    List<ToolExecutionRequest> unsupportedToolRequests = unsupportedToolRequests(aiMessage.toolExecutionRequests());
                    if (!unsupportedToolRequests.isEmpty()) {
                        return finishCommand(unsupportedToolMessage(unsupportedToolRequests));
                    }
                    if (state.toolIterationCount() >= MAX_TOOL_ITERATIONS) {
                        return finishCommand(maxToolIterationsMessage());
                    }
                    return new Command(USE_TOOLS);
                })
                .orElseGet(() -> new Command(FINISH));
    }

    private Command routeAfterTool(ChatHarnessState state, RunnableConfig config) {
        return searchFailureMessage(state.messages())
                .map(this::finishCommand)
                .orElseGet(() -> new Command(CONTINUE));
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
        modelMessages.add(SystemMessage.from(toolSystemPrompt(availableToolNames())));
        modelMessages.addAll(messages);
        return modelMessages;
    }

    private Set<String> availableToolNames() {
        return toolService.toolSpecifications().stream()
                .map(specification -> specification.name())
                .collect(Collectors.toUnmodifiableSet());
    }

    private String toolSystemPrompt(Set<String> toolNames) {
        StringBuilder prompt = new StringBuilder("""
                You may use available tools when needed.
                If the user references an uncertain path or file name and file tools are available, call find_files or list_dir before read_file.
                When read_file returns hasMore: true and you still need more content, call read_file again with nextStartLine.
                """);

        if (toolNames.contains(WEB_SEARCH_TOOL)) {
            prompt.append("""
                    Use web_search for current or external public information such as news, versions, prices, policy, or facts not already known from the conversation.
                    When you use web_search, answer only from the returned results and include source links for claims based on those results.
                    If web_search returns no results, say that no reliable search results were found and do not invent sources.
                    """);
        } else {
            prompt.append("""
                    Web search is not available in this session.
                    If the user asks you to search the web, browse, look up current information, or verify latest external facts, say that search is not enabled and do not invent sources or citations.
                    """);
        }

        if (toolNames.contains(DELEGATE_TASK_TOOL)) {
            prompt.append("""
                    Use delegate_task only when the user request contains an independent task that should be handled by a subagent.
                    For phase one, the only available subagent code is simple_task_subagent.
                    Do not invent other subagent codes.
                    """);
        }

        return prompt.toString();
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
                ChatHarnessState.CURRENT_ASSISTANT_MESSAGE, message,
                ChatHarnessState.CURRENT_ASSISTANT_THINKING, "",
                MessagesState.MESSAGES_STATE, List.of(AiMessage.from(message))
        ));
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
