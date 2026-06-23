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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
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
import org.example.weflow.core.agent.AgentRuntimeLimits;
import org.example.weflow.core.agent.AgentSpec;
import org.example.weflow.core.agent.AgentType;
import org.example.weflow.core.service.dto.ChatStreamChunk;

@Slf4j
public class AgentGraphFactory {

    static final String TURN_INITIALIZATION_NODE = "turn_initialization_node";
    static final String MODEL_NODE = "model_node";
    static final String TOOL_NODE = "tool_node";

    private static final String USE_TOOLS = "use_tools";
    private static final String FINISH = "finish";
    private static final String CONTINUE = "continue";
    private static final String WEB_SEARCH_TOOL = "web_search";
    private static final String WEB_FETCH_TOOL = "web_fetch";
    private static final Set<String> WEB_TOOLS = Set.of(WEB_SEARCH_TOOL, WEB_FETCH_TOOL);
    private static final String FAILURE_MAX_LOOPS_EXCEEDED = "AGENT_MAX_LOOPS_EXCEEDED";
    private static final String FAILURE_LLM_TIMEOUT = "AGENT_LLM_TIMEOUT";
    private static final String FAILURE_SUB_AGENT_TIMEOUT = "SUB_AGENT_TIMEOUT";
    private static final String FAILURE_LEAD_TOOL_CALL_LIMIT_EXCEEDED = "LEAD_TOOL_CALL_LIMIT_EXCEEDED";

    private final StreamingChatModel streamingChatModel;
    private final LC4jToolService toolService;
    private final AgentStreamSinkRegistry sinkRegistry = new AgentStreamSinkRegistry();

    public AgentGraphFactory(StreamingChatModel streamingChatModel, LC4jToolService toolService) {
        this.streamingChatModel = streamingChatModel;
        this.toolService = toolService;
    }

    public AgentStreamSinkRegistry sinkRegistry() {
        return sinkRegistry;
    }

    public CompiledGraph<AgentThreadState> create(AgentSpec spec) {
        try {
            GraphRuntime runtime = new GraphRuntime(spec);
            StateGraph<AgentThreadState> graph = new StateGraph<>(
                    MessagesState.SCHEMA,
                    new LC4jStateSerializer<>(AgentThreadState::new)
            );

            graph.addNode(TURN_INITIALIZATION_NODE, node_async(runtime::initializeTurn))
                    .addNode(MODEL_NODE,
                            org.bsc.langgraph4j.action.AsyncNodeActionWithConfig.node_async(runtime::modelNode))
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
            Map<String, Object> update = new LinkedHashMap<>();
            update.put(MessagesState.MESSAGES_STATE, List.of(UserMessage.from(state.currentUserMessage())));
            update.put(AgentThreadState.CURRENT_ASSISTANT_THINKING, "");
            update.put(AgentThreadState.LOOP_COUNT, 0);
            update.put(AgentThreadState.FAILURE_CODE, "");
            update.put(AgentThreadState.FAILURE_MESSAGE, "");
            update.put(AgentThreadState.LEAD_TOOL_CALL_COUNTS, Map.of());
            if (spec.runtimeLimits().hasOverallTimeout()) {
                update.put(AgentThreadState.DEADLINE_EPOCH_MILLIS,
                        Instant.now().plus(spec.runtimeLimits().overallTimeout()).toEpochMilli());
            }
            return update;
        }

        private Map<String, Object> modelNode(AgentThreadState state, RunnableConfig config) {
            Optional<Map<String, Object>> failure = failureBeforeModel(state);
            if (failure.isPresent()) {
                return failure.get();
            }
            log.info("[{} modelNode] messages count: {}", spec.definition().code(), state.messages().size());
            ChatRequest chatRequest = ChatRequest.builder()
                    .messages(messagesForModel(state.messages()))
                    .toolSpecifications(availableToolSpecifications())
                    .build();
            Duration effectiveTimeout = effectiveLlmTimeout(state);
            boolean timeoutUsesOverallDeadline = timeoutUsesOverallDeadline(state, effectiveTimeout);
            AiMessage aiMessage;
            try {
                Consumer<ChatStreamChunk> sink = config.threadId()
                        .flatMap(sinkRegistry::sink)
                        .orElse(null);
                aiMessage = chat(chatRequest, effectiveTimeout, sink);
            } catch (RuntimeException e) {
                if (isTimeout(e)) {
                    return timeoutUsesOverallDeadline
                            ? failureUpdate(FAILURE_SUB_AGENT_TIMEOUT, subAgentTimeoutMessage())
                            : failureUpdate(FAILURE_LLM_TIMEOUT, llmTimeoutMessage(spec.runtimeLimits().llmTimeout()));
                }
                throw e;
            }
            return Map.of(
                    MessagesState.MESSAGES_STATE, List.of(aiMessage),
                    AgentThreadState.CURRENT_ASSISTANT_MESSAGE, assistantText(aiMessage),
                    AgentThreadState.CURRENT_ASSISTANT_THINKING, assistantThinking(aiMessage),
                    AgentThreadState.LOOP_COUNT, state.loopCount() + 1
            );
        }

        private Command routeAfterModel(AgentThreadState state, RunnableConfig config) {
            if (state.hasFailure()) {
                return new Command(FINISH);
            }
            return lastAiMessage(state)
                    .filter(AiMessage::hasToolExecutionRequests)
                    .map(aiMessage -> {
                        List<ToolExecutionRequest> unsupportedToolRequests =
                                unsupportedToolRequests(aiMessage.toolExecutionRequests());
                        if (!unsupportedToolRequests.isEmpty()) {
                            return finishCommand(unsupportedToolMessage(unsupportedToolRequests));
                        }
                        return new Command(USE_TOOLS);
                    })
                    .orElseGet(() -> new Command(FINISH));
        }

        private Command routeAfterTool(AgentThreadState state, RunnableConfig config) {
            if (state.hasFailure()) {
                return new Command(FINISH);
            }
            return webToolFailureMessage(state.messages())
                    .map(this::finishCommand)
                    .orElseGet(() -> new Command(CONTINUE));
        }

        private Map<String, Object> toolNode(AgentThreadState state) {
            Optional<Map<String, Object>> failure = failureBeforeTool(state);
            if (failure.isPresent()) {
                return failure.get();
            }
            AiMessage aiMessage = lastAiMessage(state)
                    .filter(AiMessage::hasToolExecutionRequests)
                    .orElseThrow(() -> new IllegalStateException("Tool node requires an AI message with tool requests."));
            List<ToolExecutionRequest> toolRequests = aiMessage.toolExecutionRequests();
            ToolLimitDecision toolLimitDecision = leadToolLimitDecision(state.leadToolCallCounts(), toolRequests);
            if (toolLimitDecision.shouldStop()) {
                return failureUpdate(FAILURE_LEAD_TOOL_CALL_LIMIT_EXCEEDED,
                        leadToolStopMessage(toolLimitDecision.stopToolName(), toolLimitDecision.stopCount()));
            }

            Command command;
            try {
                command = executeTools(toolRequests, state);
            } catch (RuntimeException e) {
                if (isTimeout(e)) {
                    return failureUpdate(FAILURE_SUB_AGENT_TIMEOUT, subAgentTimeoutMessage());
                }
                throw e;
            }
            if (overallTimeoutExceeded(state)) {
                return failureUpdate(FAILURE_SUB_AGENT_TIMEOUT, subAgentTimeoutMessage());
            }

            List<ToolExecutionResultMessage> toolResults = appendLeadToolWarnings(
                    toolResultMessages(command),
                    toolLimitDecision
            );
            Map<String, Object> update = new LinkedHashMap<>();
            update.put(MessagesState.MESSAGES_STATE, toolResults);
            if (spec.runtimeLimits().hasLeadToolCallLimit()) {
                update.put(AgentThreadState.LEAD_TOOL_CALL_COUNTS, toolLimitDecision.updatedCounts());
            }
            return update;
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

        private Optional<Map<String, Object>> failureBeforeModel(AgentThreadState state) {
            if (state.hasFailure()) {
                return Optional.of(failureUpdate(state.failureCode().orElse("AGENT_EXECUTION_FAILED"),
                        state.failureMessage()));
            }
            if (overallTimeoutExceeded(state)) {
                return Optional.of(failureUpdate(FAILURE_SUB_AGENT_TIMEOUT, subAgentTimeoutMessage()));
            }
            if (state.loopCount() >= spec.runtimeLimits().maxLoops()) {
                return Optional.of(failureUpdate(FAILURE_MAX_LOOPS_EXCEEDED, maxLoopsMessage()));
            }
            return Optional.empty();
        }

        private Optional<Map<String, Object>> failureBeforeTool(AgentThreadState state) {
            if (state.hasFailure()) {
                return Optional.of(failureUpdate(state.failureCode().orElse("AGENT_EXECUTION_FAILED"),
                        state.failureMessage()));
            }
            if (overallTimeoutExceeded(state)) {
                return Optional.of(failureUpdate(FAILURE_SUB_AGENT_TIMEOUT, subAgentTimeoutMessage()));
            }
            return Optional.empty();
        }

        private Duration effectiveLlmTimeout(AgentThreadState state) {
            Duration llmTimeout = spec.runtimeLimits().llmTimeout();
            return remainingOverallTimeout(state)
                    .filter(remaining -> remaining.compareTo(llmTimeout) < 0)
                    .orElse(llmTimeout);
        }

        private boolean timeoutUsesOverallDeadline(AgentThreadState state, Duration effectiveTimeout) {
            return remainingOverallTimeout(state)
                    .map(remaining -> remaining.compareTo(spec.runtimeLimits().llmTimeout()) <= 0
                            && effectiveTimeout.compareTo(remaining) <= 0)
                    .orElse(false);
        }

        private Optional<Duration> remainingOverallTimeout(AgentThreadState state) {
            return state.deadlineEpochMillis()
                    .map(deadline -> Duration.ofMillis(deadline - Instant.now().toEpochMilli()));
        }

        private boolean overallTimeoutExceeded(AgentThreadState state) {
            return remainingOverallTimeout(state)
                    .map(remaining -> !remaining.isPositive())
                    .orElse(false);
        }

        private Command executeTools(List<ToolExecutionRequest> toolRequests, AgentThreadState state) {
            CompletableFuture<Command> commandFuture = toolService.execute(
                    toolRequests,
                    invocationContext(state),
                    MessagesState.MESSAGES_STATE
            );
            Optional<Duration> remainingOverallTimeout = remainingOverallTimeout(state);
            if (remainingOverallTimeout.isEmpty()) {
                return commandFuture.join();
            }
            Duration remaining = remainingOverallTimeout.get();
            if (remaining.isZero() || remaining.isNegative()) {
                throw new CompletionException(new TimeoutException("subagent overall timeout reached"));
            }
            return commandFuture.orTimeout(Math.max(remaining.toMillis(), 1L), TimeUnit.MILLISECONDS).join();
        }

        private ToolLimitDecision leadToolLimitDecision(
                Map<String, Integer> currentCounts,
                List<ToolExecutionRequest> toolRequests
        ) {
            if (!spec.runtimeLimits().hasLeadToolCallLimit()
                    || spec.definition().type() != AgentType.LEAD) {
                return ToolLimitDecision.allowed(currentCounts);
            }

            AgentRuntimeLimits.ToolCallLimit limit = spec.runtimeLimits().leadToolCallLimit();
            Map<String, Integer> updatedCounts = new LinkedHashMap<>(currentCounts);
            Set<String> warningToolNames = new java.util.LinkedHashSet<>();
            for (ToolExecutionRequest toolRequest : toolRequests) {
                String toolName = toolRequest.name();
                int previousCount = updatedCounts.getOrDefault(toolName, 0);
                int nextCount = previousCount + 1;
                if (nextCount >= limit.stopThreshold()) {
                    return ToolLimitDecision.stop(updatedCounts, toolName, nextCount);
                }
                if (previousCount < limit.warningThreshold() && nextCount >= limit.warningThreshold()) {
                    warningToolNames.add(toolName);
                }
                updatedCounts.put(toolName, nextCount);
            }
            return new ToolLimitDecision(updatedCounts, warningToolNames, null, 0);
        }

        private List<ToolExecutionResultMessage> appendLeadToolWarnings(
                List<ToolExecutionResultMessage> messages,
                ToolLimitDecision toolLimitDecision
        ) {
            if (toolLimitDecision.warningToolNames().isEmpty()) {
                return messages;
            }
            Set<String> remainingWarnings = new java.util.LinkedHashSet<>(toolLimitDecision.warningToolNames());
            List<ToolExecutionResultMessage> updatedMessages = new ArrayList<>(messages.size());
            for (ToolExecutionResultMessage message : messages) {
                if (remainingWarnings.remove(message.toolName())) {
                    updatedMessages.add(ToolExecutionResultMessage.builder()
                            .id(message.id())
                            .toolName(message.toolName())
                            .text((message.text() == null ? "" : message.text())
                                    + "\n\n" + leadToolWarningMessage(message.toolName()))
                            .isError(message.isError())
                            .attributes(message.attributes())
                            .build());
                } else {
                    updatedMessages.add(message);
                }
            }
            return updatedMessages;
        }

        private Map<String, Object> failureUpdate(String code, String message) {
            return Map.of(
                    AgentThreadState.FAILURE_CODE, code,
                    AgentThreadState.FAILURE_MESSAGE, message,
                    AgentThreadState.CURRENT_ASSISTANT_MESSAGE, controlledFailureText(code, message),
                    AgentThreadState.CURRENT_ASSISTANT_THINKING, "",
                    MessagesState.MESSAGES_STATE, List.of(AiMessage.from(controlledFailureText(code, message)))
            );
        }

        private String controlledFailureText(String code, String message) {
            return "status: error\n"
                    + "code: " + code + "\n"
                    + "message:\n"
                    + message + "\n";
        }

        private String maxLoopsMessage() {
            return "Agent execution stopped because the maximum number of LLM loops was reached: "
                    + spec.runtimeLimits().maxLoops() + ".";
        }

        private String llmTimeoutMessage(Duration timeout) {
            return "Agent execution stopped because a single LLM call exceeded the configured timeout of "
                    + timeout.toSeconds() + " seconds.";
        }

        private String subAgentTimeoutMessage() {
            return "Subagent execution stopped because the overall timeout was reached.";
        }

        private String leadToolWarningMessage(String toolName) {
            AgentRuntimeLimits.ToolCallLimit limit = spec.runtimeLimits().leadToolCallLimit();
            return "warning:\n"
                    + "code: LEAD_TOOL_CALL_WARNING\n"
                    + "message: Tool " + toolName + " has reached "
                    + limit.warningThreshold()
                    + " calls in this request. Change strategy before the hard stop at "
                    + limit.stopThreshold()
                    + " calls.";
        }

        private String leadToolStopMessage(String toolName, int count) {
            AgentRuntimeLimits.ToolCallLimit limit = spec.runtimeLimits().leadToolCallLimit();
            return "Tool execution stopped because tool " + toolName + " reached "
                    + count + " calls in this request. The hard stop threshold is "
                    + limit.stopThreshold() + ".";
        }

        private boolean isTimeout(Throwable throwable) {
            Throwable current = throwable;
            while (current instanceof CompletionException && current.getCause() != null) {
                current = current.getCause();
            }
            return current instanceof TimeoutException;
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
            boolean includesWebFetch = unsupportedToolRequests.stream()
                    .anyMatch(toolRequest -> WEB_FETCH_TOOL.equals(toolRequest.name()));
            if (includesWebFetch) {
                return "网页读取功能未启用，当前无法抓取网页内容。请启用 we-flow.fetch.enabled=true 后重试。";
            }

            String toolNames = unsupportedToolRequests.stream()
                    .map(ToolExecutionRequest::name)
                    .distinct()
                    .collect(Collectors.joining(", "));
            return "请求的工具不可用：" + toolNames + "，无法执行该操作。";
        }

        private java.util.Optional<String> webToolFailureMessage(List<ChatMessage> messages) {
            return latestToolResultMessages(messages).stream()
                    .filter(ToolExecutionResultMessage.class::isInstance)
                    .map(ToolExecutionResultMessage.class::cast)
                    .filter(message -> WEB_TOOLS.contains(message.toolName()))
                    .filter(message -> isToolError(message.text()))
                    .map(message -> webToolFailureUserMessage(message.toolName(), message.text()))
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

        private String webToolFailureUserMessage(String toolName, String toolResultText) {
            if (WEB_FETCH_TOOL.equals(toolName)) {
                return fetchFailureUserMessage(toolResultText);
            }
            return searchFailureUserMessage(toolResultText);
        }

        private String fetchFailureUserMessage(String toolResultText) {
            String message = extractToolErrorMessage(toolResultText)
                    .orElse("网页读取工具返回错误。");
            return "网页读取失败：" + message + "。我没有获取到可靠网页内容，因此不会继续基于该页面生成结论。";
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

    private record ToolLimitDecision(
            Map<String, Integer> updatedCounts,
            Set<String> warningToolNames,
            String stopToolName,
            int stopCount
    ) {

        private static ToolLimitDecision allowed(Map<String, Integer> currentCounts) {
            return new ToolLimitDecision(currentCounts, Set.of(), null, 0);
        }

        private static ToolLimitDecision stop(Map<String, Integer> updatedCounts, String stopToolName, int stopCount) {
            return new ToolLimitDecision(updatedCounts, Set.of(), stopToolName, stopCount);
        }

        private boolean shouldStop() {
            return stopToolName != null;
        }
    }

    private AiMessage chat(ChatRequest chatRequest, Duration timeout, Consumer<ChatStreamChunk> contentSink) {
        CompletableFuture<AiMessage> responseFuture = new CompletableFuture<>();
        StringBuilder responseBuilder = new StringBuilder();
        StringBuilder thinkingBuilder = new StringBuilder();

        streamingChatModel.chat(chatRequest, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                responseBuilder.append(partialResponse);
                if (contentSink != null && partialResponse != null && !partialResponse.isEmpty()) {
                    contentSink.accept(ChatStreamChunk.content(partialResponse));
                }
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

        return responseFuture.orTimeout(Math.max(timeout.toMillis(), 1L), TimeUnit.MILLISECONDS).join();
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
