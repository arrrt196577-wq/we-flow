package org.example.weflow.agent.tool;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import java.time.Instant;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.example.weflow.agent.subagent.SubAgentRegistry;
import org.example.weflow.core.agent.AgentContext;
import org.example.weflow.core.agent.AgentExecutor;
import org.example.weflow.core.agent.AgentResult;
import org.example.weflow.core.agent.AgentStatus;
import org.example.weflow.core.agent.AgentTask;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Slf4j
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
            @P("Registered subagent code from the available subagent list.") String subAgentCode,
            @P("Task type, for example general_task.") String taskType,
            @P("Clear objective for the subagent.") String objective,
            @P(value = "Task input as plain text or a JSON string.", required = false) String input
    ) {
        log.info("Tool called: delegate_task subAgentCode={}, taskType={}, objective={}, inputLength={}",
                sanitize(subAgentCode), sanitize(taskType), sanitize(objective), input == null ? 0 : input.length());
        String taskId = UUID.randomUUID().toString();
        String traceId = UUID.randomUUID().toString();
        Instant startedAt = Instant.now();
        if (!StringUtils.hasText(subAgentCode)) {
            return error(taskId, traceId, null, "INVALID_ARGUMENT", "subAgentCode must not be blank", startedAt);
        }
        if (!StringUtils.hasText(taskType)) {
            return error(taskId, traceId, subAgentCode, "INVALID_ARGUMENT", "taskType must not be blank", startedAt);
        }
        if (!StringUtils.hasText(objective)) {
            return error(taskId, traceId, subAgentCode, "INVALID_ARGUMENT", "objective must not be blank", startedAt);
        }

        AgentExecutor subAgent = subAgentRegistry.findByCode(subAgentCode)
                .orElse(null);
        if (subAgent == null) {
            return error(taskId, traceId, subAgentCode, "SUB_AGENT_NOT_FOUND",
                    "subagent not found: " + sanitize(subAgentCode), startedAt);
        }

        AgentTask task = new AgentTask(taskId, taskType.trim(), objective.trim(), input == null ? "" : input);
        AgentResult result;
        try {
            result = subAgent.execute(task, new AgentContext(LEAD_AGENT_CODE, traceId));
        } catch (RuntimeException e) {
            return error(taskId, traceId, subAgentCode, "SUB_AGENT_EXECUTION_FAILED",
                    safeBlock(e.getMessage()), startedAt);
        }

        if (result.status() != AgentStatus.SUCCESS) {
            return error(result.taskId(), result.traceId(), result.agentCode(), result.errorCode(),
                    result.errorMessage(), result.startedAt(), result.completedAt());
        }

        return "status: success\n"
                + "taskId: " + sanitize(result.taskId()) + "\n"
                + "traceId: " + sanitize(result.traceId()) + "\n"
                + "subAgent: " + sanitize(result.agentCode()) + "\n"
                + "taskType: " + sanitize(task.taskType()) + "\n"
                + "startedAt: " + result.startedAt() + "\n"
                + "completedAt: " + result.completedAt() + "\n"
                + "result:\n"
                + safeBlock(result.output()) + "\n";
    }

    private String error(
            String taskId,
            String traceId,
            String subAgentCode,
            String code,
            String message,
            Instant startedAt
    ) {
        return error(taskId, traceId, subAgentCode, code, message, startedAt, Instant.now());
    }

    private String error(
            String taskId,
            String traceId,
            String subAgentCode,
            String code,
            String message,
            Instant startedAt,
            Instant completedAt
    ) {
        StringBuilder result = new StringBuilder()
                .append("status: error\n")
                .append("taskId: ").append(sanitize(taskId)).append('\n')
                .append("traceId: ").append(sanitize(traceId)).append('\n');
        if (StringUtils.hasText(subAgentCode)) {
            result.append("subAgent: ").append(sanitize(subAgentCode)).append('\n');
        }
        return result
                .append("code: ").append(sanitize(code)).append('\n')
                .append("startedAt: ").append(startedAt).append('\n')
                .append("completedAt: ").append(completedAt).append('\n')
                .append("message:\n")
                .append(safeBlock(message)).append('\n')
                .toString();
    }

    private String error(String code, String message) {
        String taskId = UUID.randomUUID().toString();
        String traceId = UUID.randomUUID().toString();
        Instant startedAt = Instant.now();
        return error(taskId, traceId, null, code, message, startedAt, startedAt);
    }

    private String safeBlock(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\r\n", "\n").replace('\r', '\n').trim();
    }

    private String sanitize(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
    }
}
