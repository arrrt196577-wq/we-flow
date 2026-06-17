package org.example.weflow.agent.subagent;

import org.example.weflow.core.agent.AgentContext;
import org.example.weflow.core.agent.AgentDefinition;
import org.example.weflow.core.agent.AgentExecutor;
import org.example.weflow.core.agent.AgentResult;
import org.example.weflow.core.agent.AgentTask;
import org.example.weflow.core.agent.AgentType;

public class SimpleTaskSubAgentExecutor implements AgentExecutor {

    public static final String CODE = "simple_task_subagent";

    private static final AgentDefinition DEFINITION = new AgentDefinition(
            CODE,
            "Simple Task SubAgent",
            AgentType.SUB,
            "Deterministic subagent used to verify task delegation.",
            true
    );

    @Override
    public AgentDefinition definition() {
        return DEFINITION;
    }

    @Override
    public AgentResult execute(AgentTask task, AgentContext context) {
        String output = "accepted task"
                + "; parentAgentCode=" + safe(context == null ? null : context.parentAgentCode())
                + "; taskType=" + safe(task.taskType())
                + "; objective=" + safe(task.objective())
                + "; input=" + safe(task.input());
        return AgentResult.success(task.taskId(), CODE, output);
    }

    private String safe(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
    }
}
