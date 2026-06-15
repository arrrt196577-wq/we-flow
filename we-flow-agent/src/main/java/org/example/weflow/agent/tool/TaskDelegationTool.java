package org.example.weflow.agent.tool;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import java.util.UUID;
import org.example.weflow.agent.subagent.SubAgentRegistry;
import org.example.weflow.core.agent.AgentContext;
import org.example.weflow.core.agent.AgentExecutor;
import org.example.weflow.core.agent.AgentResult;
import org.example.weflow.core.agent.AgentStatus;
import org.example.weflow.core.agent.AgentTask;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@ConditionalOnProperty(prefix = "we-flow.agent.delegation", name = "enabled", havingValue = "true")
public class TaskDelegationTool implements AgentTool {

    private static final String LEAD_AGENT_CODE = "lead_agent";

    private final SubAgentRegistry subAgentRegistry;

    public TaskDelegationTool(SubAgentRegistry subAgentRegistry) {
        this.subAgentRegistry = subAgentRegistry;
    }

    @Tool(name = "delegate_task", value = "Delegate an independent task to a registered subagent.")
    public String delegateTask(
            @P("Registered subagent code. For phase one use simple_task_subagent.") String subAgentCode,
            @P("Task type, for example general_task.") String taskType,
            @P("Clear objective for the subagent.") String objective,
            @P(value = "Task input as plain text or a JSON string.", required = false) String input
    ) {
        if (!StringUtils.hasText(subAgentCode)) {
            return error("INVALID_ARGUMENT", "subAgentCode must not be blank");
        }
        if (!StringUtils.hasText(taskType)) {
            return error("INVALID_ARGUMENT", "taskType must not be blank");
        }
        if (!StringUtils.hasText(objective)) {
            return error("INVALID_ARGUMENT", "objective must not be blank");
        }

        String taskId = UUID.randomUUID().toString();
        AgentExecutor subAgent = subAgentRegistry.findByCode(subAgentCode)
                .orElse(null);
        if (subAgent == null) {
            return "status: error\n"
                    + "taskId: " + taskId + "\n"
                    + "code: SUB_AGENT_NOT_FOUND\n"
                    + "message: subagent not found: " + sanitize(subAgentCode) + "\n";
        }

        AgentTask task = new AgentTask(taskId, taskType.trim(), objective.trim(), input == null ? "" : input);
        AgentResult result;
        try {
            result = subAgent.execute(task, new AgentContext(LEAD_AGENT_CODE));
        } catch (RuntimeException e) {
            return "status: error\n"
                    + "taskId: " + taskId + "\n"
                    + "subAgent: " + sanitize(subAgentCode) + "\n"
                    + "code: SUB_AGENT_EXECUTION_FAILED\n"
                    + "message: " + sanitize(e.getMessage()) + "\n";
        }

        if (result.status() != AgentStatus.SUCCESS) {
            return "status: error\n"
                    + "taskId: " + taskId + "\n"
                    + "subAgent: " + sanitize(result.agentCode()) + "\n"
                    + "code: " + sanitize(result.errorCode()) + "\n"
                    + "message: " + sanitize(result.errorMessage()) + "\n";
        }

        return "status: success\n"
                + "taskId: " + sanitize(result.taskId()) + "\n"
                + "subAgent: " + sanitize(result.agentCode()) + "\n"
                + "taskType: " + sanitize(task.taskType()) + "\n"
                + "output: " + sanitize(result.output()) + "\n";
    }

    private String error(String code, String message) {
        return "status: error\n"
                + "code: " + code + "\n"
                + "message: " + sanitize(message) + "\n";
    }

    private String sanitize(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
    }
}
