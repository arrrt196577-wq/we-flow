package org.example.weflow.workflow.agent.runtime;

import dev.langchain4j.data.message.AiMessage;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.bsc.langgraph4j.action.Command;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.example.weflow.workflow.agent.AgentThreadState;

public final class MiddlewareManager {

    private final List<WeflowMiddleware> middlewares;

    public MiddlewareManager(List<WeflowMiddleware> middlewares) {
        this.middlewares = middlewares == null ? List.of() : List.copyOf(middlewares);
    }

    public static MiddlewareManager empty() {
        return new MiddlewareManager(List.of());
    }

    public Optional<MiddlewareResult> onRunStart(AgentRunContext context) {
        return firstBlocking(middleware -> middleware.onRunStart(context));
    }

    public Optional<MiddlewareResult> beforeModel(ModelCallContext context) {
        return firstBlocking(middleware -> middleware.beforeModel(context));
    }

    public AiMessage aroundModel(ModelCallContext context, WeflowMiddleware.ModelCall terminal) {
        return invokeModelMiddlewareChain(0, context, terminal);
    }

    public Optional<MiddlewareResult> afterModel(ModelCallContext context, AiMessage aiMessage) {
        return firstBlocking(middleware -> middleware.afterModel(context, aiMessage));
    }

    public Optional<MiddlewareResult> beforeTool(ToolCallContext context) {
        return firstBlocking(middleware -> middleware.beforeTool(context));
    }

    public Command aroundTool(ToolCallContext context, WeflowMiddleware.ToolCall terminal) {
        return invokeToolMiddlewareChain(0, context, terminal);
    }

    public Optional<MiddlewareResult> afterTool(ToolCallContext context, Command command) {
        return firstBlocking(middleware -> middleware.afterTool(context, command));
    }

    public Optional<MiddlewareResult> beforeFinish(FinishContext context) {
        return firstBlocking(middleware -> middleware.beforeFinish(context));
    }

    public Optional<MiddlewareResult> onRunEnd(AgentRunContext context) {
        return firstBlocking(middleware -> middleware.onRunEnd(context));
    }

    public Map<String, Object> failureUpdate(String code, String message) {
        String failureText = controlledFailureText(code, message);
        return Map.of(
                AgentThreadState.FAILURE_CODE, code,
                AgentThreadState.FAILURE_MESSAGE, message,
                AgentThreadState.CURRENT_ASSISTANT_MESSAGE, failureText,
                AgentThreadState.CURRENT_ASSISTANT_THINKING, "",
                MessagesState.MESSAGES_STATE, List.of(AiMessage.from(failureText))
        );
    }

    public String controlledFailureText(String code, String message) {
        return "status: error\n"
                + "code: " + code + "\n"
                + "message:\n"
                + message + "\n";
    }

    private Optional<MiddlewareResult> firstBlocking(Function<WeflowMiddleware, MiddlewareResult> callback) {
        for (WeflowMiddleware middleware : middlewares) {
            MiddlewareResult result = callback.apply(middleware);
            if (result != null && !result.isContinue()) {
                return Optional.of(result);
            }
        }
        return Optional.empty();
    }

    private AiMessage invokeModelMiddlewareChain(
            int index,
            ModelCallContext context,
            WeflowMiddleware.ModelCall terminal
    ) {
        if (index >= middlewares.size()) {
            return terminal.call(context);
        }
        WeflowMiddleware middleware = middlewares.get(index);
        return middleware.aroundModel(
                context,
                nextContext -> invokeModelMiddlewareChain(index + 1, nextContext, terminal)
        );
    }

    private Command invokeToolMiddlewareChain(
            int index,
            ToolCallContext context,
            WeflowMiddleware.ToolCall terminal
    ) {
        if (index >= middlewares.size()) {
            return terminal.call(context);
        }
        WeflowMiddleware middleware = middlewares.get(index);
        return middleware.aroundTool(
                context,
                nextContext -> invokeToolMiddlewareChain(index + 1, nextContext, terminal)
        );
    }
}
