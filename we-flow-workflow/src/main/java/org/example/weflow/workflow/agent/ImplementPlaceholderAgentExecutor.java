package org.example.weflow.workflow.agent;

import java.time.Instant;
import java.util.UUID;
import org.example.weflow.core.agent.AgentContext;
import org.example.weflow.core.agent.AgentDefinition;
import org.example.weflow.core.agent.AgentExecutor;
import org.example.weflow.core.agent.AgentResult;
import org.example.weflow.core.agent.AgentTask;

public class ImplementPlaceholderAgentExecutor implements AgentExecutor {

    private static final String ERROR_CODE = "IMPLEMENT_AGENT_NOT_ENABLED";

    @Override
    public AgentDefinition definition() {
        return DefaultAgentSpecs.implementAgentDefinition();
    }

    @Override
    public AgentResult execute(AgentTask task, AgentContext context) {
        Instant startedAt = Instant.now();
        return AgentResult.failed(
                task.taskId(),
                traceId(context),
                DefaultAgentSpecs.IMPLEMENT_AGENT_CODE,
                ERROR_CODE,
                "implement_agent 尚未启用执行能力；第一阶段不会写入文件或执行命令。",
                startedAt,
                Instant.now()
        );
    }

    private String traceId(AgentContext context) {
        if (context != null && context.traceId() != null && !context.traceId().isBlank()) {
            return context.traceId();
        }
        return UUID.randomUUID().toString();
    }
}
