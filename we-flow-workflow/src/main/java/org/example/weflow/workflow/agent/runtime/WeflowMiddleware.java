package org.example.weflow.workflow.agent.runtime;

import dev.langchain4j.data.message.AiMessage;
import org.bsc.langgraph4j.action.Command;

public interface WeflowMiddleware {

    default MiddlewareResult onRunStart(AgentRunContext context) {
        return MiddlewareResult.continueProcessing();
    }

    default MiddlewareResult beforeModel(ModelCallContext context) {
        return MiddlewareResult.continueProcessing();
    }

    default AiMessage aroundModel(ModelCallContext context, ModelCall next) {
        return next.call(context);
    }

    default MiddlewareResult afterModel(ModelCallContext context, AiMessage aiMessage) {
        return MiddlewareResult.continueProcessing();
    }

    default MiddlewareResult beforeTool(ToolCallContext context) {
        return MiddlewareResult.continueProcessing();
    }

    default Command aroundTool(ToolCallContext context, ToolCall next) {
        return next.call(context);
    }

    default MiddlewareResult afterTool(ToolCallContext context, Command command) {
        return MiddlewareResult.continueProcessing();
    }

    default MiddlewareResult beforeFinish(FinishContext context) {
        return MiddlewareResult.continueProcessing();
    }

    default MiddlewareResult onRunEnd(AgentRunContext context) {
        return MiddlewareResult.continueProcessing();
    }

    @FunctionalInterface
    interface ModelCall {
        AiMessage call(ModelCallContext context);
    }

    @FunctionalInterface
    interface ToolCall {
        Command call(ToolCallContext context);
    }
}
