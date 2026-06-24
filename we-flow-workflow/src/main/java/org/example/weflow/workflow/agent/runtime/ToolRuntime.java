package org.example.weflow.workflow.agent.runtime;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.invocation.InvocationParameters;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.bsc.langgraph4j.action.Command;
import org.bsc.langgraph4j.langchain4j.tool.LC4jToolService;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.example.weflow.workflow.agent.AgentThreadState;

public final class ToolRuntime {

    private final LC4jToolService toolService;
    private final MiddlewareManager middlewareManager;
    private final String invocationInterfaceName;

    public ToolRuntime(
            LC4jToolService toolService,
            MiddlewareManager middlewareManager,
            String invocationInterfaceName
    ) {
        this.toolService = toolService;
        this.middlewareManager = middlewareManager;
        this.invocationInterfaceName = invocationInterfaceName;
    }

    public Command execute(
            AgentRunContext runContext,
            List<ToolExecutionRequest> toolRequests,
            AgentThreadState state,
            Optional<Duration> remainingOverallTimeout
    ) {
        ToolCallContext context = new ToolCallContext(
                runContext,
                state,
                toolRequests,
                invocationContext(state),
                remainingOverallTimeout
        );
        return middlewareManager.aroundTool(context, this::execute);
    }

    @SuppressWarnings("unchecked")
    public List<ToolExecutionResultMessage> toolResultMessages(Command command) {
        Object update = command.update().get(MessagesState.MESSAGES_STATE);
        if (update instanceof List<?> messages) {
            return (List<ToolExecutionResultMessage>) messages;
        }
        return List.of();
    }

    public boolean isTimeout(Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current instanceof TimeoutException;
    }

    private InvocationContext invocationContext(AgentThreadState state) {
        return InvocationContext.builder()
                .invocationId(UUID.randomUUID())
                .interfaceName(invocationInterfaceName)
                .methodName("toolNode")
                .methodArguments(List.of(state.currentUserMessage()))
                .chatMemoryId(state.value("thread_id").orElse(null))
                .invocationParameters(InvocationParameters.from(Map.of()))
                .timestamp(Instant.now())
                .build();
    }

    private Command execute(ToolCallContext context) {
        CompletableFuture<Command> commandFuture = toolService.execute(
                context.toolRequests(),
                context.invocationContext(),
                MessagesState.MESSAGES_STATE
        );
        Optional<Duration> remainingOverallTimeout = context.remainingOverallTimeout();
        if (remainingOverallTimeout.isEmpty()) {
            return commandFuture.join();
        }
        Duration remaining = remainingOverallTimeout.get();
        if (remaining.isZero() || remaining.isNegative()) {
            throw new CompletionException(new TimeoutException("subagent overall timeout reached"));
        }
        return commandFuture.orTimeout(Math.max(remaining.toMillis(), 1L), TimeUnit.MILLISECONDS).join();
    }
}
