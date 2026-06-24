package org.example.weflow.workflow.agent.runtime;

import dev.langchain4j.data.message.AiMessage;
import java.util.Map;
import org.bsc.langgraph4j.action.Command;
import org.example.weflow.workflow.agent.AgentThreadState;

/**
 * Agent 运行期中间件扩展点，提供两种风格的钩子，按需重写需要的方法即可。
 *
 * <p>命名遵循统一语法 {@code <边界><阶段>}：边界为 before / around / after，
 * 阶段为 Run / Model / Tool / Finish，详见 docs/agent-middleware-conventions.md。
 *
 * <p>风格一 · 阻断式钩子（beforeRun / beforeModel / afterModel / beforeTool /
 * afterTool / beforeFinish / afterFinish / afterRun）：在生命周期某个时间点被回调，只“看一眼并表态”，
 * 不持有真实调用；返回 {@link MiddlewareResult}（CONTINUE 放行 / SHORT_CIRCUIT 短路 /
 * RETRY 重试 / FAIL 失败）。多个中间件按注册顺序执行，遇到第一个非 CONTINUE 即停。
 * 适用：校验、放行/拦截、上报指标、改写 state 等“旁观决策”场景。
 *
 * <p>风格二 · 环绕式钩子（aroundRun / aroundModel / aroundTool / aroundFinish）：真实调用被包在内部，
 * 通过 {@code next} 续延自行决定调用时机，调用前后均可插入逻辑，多个中间件形成洋葱式嵌套（先注册的在外层）；
 * 直接返回领域对象（AgentThreadState / AiMessage / Command / Map）。
 * 适用：计时、try/finally 兜底、整段重试、改写请求/响应、缓存命中直接返回等“包裹调用”场景。
 *
 * <p>选择原则：仅需观察或拦截，用风格一；需要把调用夹在中间、改写输入输出或兜底异常，才用风格二。
 * 注意 aroundModel 包裹的是流式调用，若不调用 {@code next} 直接返回，会跳过 streaming（partialSink 收不到增量）。
 */
public interface WeflowMiddleware {

    default MiddlewareResult beforeRun(AgentRunContext context) {
        return MiddlewareResult.continueProcessing();
    }

    default AgentThreadState aroundRun(AgentRunContext context, RunCall next) {
        return next.call(context);
    }

    default MiddlewareResult afterRun(AgentRunContext context) {
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

    default Map<String, Object> aroundFinish(FinishContext context, FinishCall next) {
        return next.call(context);
    }

    default MiddlewareResult afterFinish(FinishContext context) {
        return MiddlewareResult.continueProcessing();
    }

    @FunctionalInterface
    interface RunCall {
        AgentThreadState call(AgentRunContext context);
    }

    @FunctionalInterface
    interface ModelCall {
        AiMessage call(ModelCallContext context);
    }

    @FunctionalInterface
    interface ToolCall {
        Command call(ToolCallContext context);
    }

    @FunctionalInterface
    interface FinishCall {
        Map<String, Object> call(FinishContext context);
    }
}
