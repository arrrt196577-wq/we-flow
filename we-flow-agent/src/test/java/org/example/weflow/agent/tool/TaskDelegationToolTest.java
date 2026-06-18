package org.example.weflow.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;

import dev.langchain4j.agent.tool.Tool;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.example.weflow.agent.config.AgentDelegationConfiguration;
import org.example.weflow.agent.subagent.InMemorySubAgentRegistry;
import org.example.weflow.agent.subagent.SimpleTaskSubAgentExecutor;
import org.example.weflow.core.agent.AgentContext;
import org.example.weflow.core.agent.AgentDefinition;
import org.example.weflow.core.agent.AgentExecutor;
import org.example.weflow.core.agent.AgentResult;
import org.example.weflow.core.agent.AgentTask;
import org.example.weflow.core.agent.AgentType;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class TaskDelegationToolTest {

    @Test
    void shouldExposeDelegateTaskLangChainTool() {
        Set<String> toolNames = Arrays.stream(TaskDelegationTool.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(Tool.class))
                .filter(annotation -> annotation != null)
                .map(Tool::name)
                .collect(Collectors.toSet());

        assertThat(toolNames).containsExactly("delegate_task");
    }

    @Test
    void delegateTaskShouldReturnSubAgentResult() {
        TaskDelegationTool tool = tool();

        String result = tool.delegateTask(
                SimpleTaskSubAgentExecutor.CODE,
                "general_task",
                "verify delegation",
                "{\"value\":1}"
        );

        assertThat(result).contains("status: success");
        assertThat(result).contains("traceId:");
        assertThat(result).contains("subAgent: " + SimpleTaskSubAgentExecutor.CODE);
        assertThat(result).contains("taskType: general_task");
        assertThat(result).contains("startedAt:");
        assertThat(result).contains("completedAt:");
        assertThat(result).contains("result:\naccepted task");
        assertThat(result).contains("objective=verify delegation");
        assertThat(result).contains("input={\"value\":1}");
    }

    @Test
    void delegateTaskShouldPreserveMultilineResultAndPassTraceId() {
        AtomicReference<AgentContext> capturedContext = new AtomicReference<>();
        TaskDelegationTool tool = new TaskDelegationTool(new InMemorySubAgentRegistry(List.of(new AgentExecutor() {
            @Override
            public AgentDefinition definition() {
                return new AgentDefinition("multi_line_subagent", "Multi Line SubAgent", AgentType.SUB, "test", true);
            }

            @Override
            public AgentResult execute(AgentTask task, AgentContext context) {
                capturedContext.set(context);
                Instant startedAt = Instant.now();
                return AgentResult.success(task.taskId(), context.traceId(), "multi_line_subagent",
                        "line 1\nline 2", startedAt, Instant.now());
            }
        })));

        String result = tool.delegateTask("multi_line_subagent", "general_task", "verify multiline", "");

        assertThat(capturedContext.get()).isNotNull();
        assertThat(capturedContext.get().traceId()).isNotBlank();
        assertThat(result).contains("traceId: " + capturedContext.get().traceId());
        assertThat(result).contains("result:\nline 1\nline 2");
    }

    @Test
    void delegateTaskShouldReturnErrorWhenSubAgentIsUnknown() {
        TaskDelegationTool tool = tool();

        String result = tool.delegateTask("missing_subagent", "general_task", "verify delegation", "");

        assertThat(result).contains("status: error");
        assertThat(result).contains("traceId:");
        assertThat(result).contains("code: SUB_AGENT_NOT_FOUND");
        assertThat(result).contains("subagent not found: missing_subagent");
    }

    @Test
    void delegationToolBeanShouldBeDisabledByDefault() {
        new ApplicationContextRunner()
                .withBean(AgentExecutor.class, SimpleTaskSubAgentExecutor::new)
                .withUserConfiguration(
                        AgentDelegationConfiguration.class,
                        InMemorySubAgentRegistry.class,
                        TaskDelegationTool.class
                )
                .run(context -> assertThat(context).doesNotHaveBean(TaskDelegationTool.class));
    }

    @Test
    void delegationToolBeanShouldBeEnabledWhenConfigured() {
        new ApplicationContextRunner()
                .withPropertyValues("we-flow.agent.delegation.enabled=true")
                .withBean(AgentExecutor.class, SimpleTaskSubAgentExecutor::new)
                .withUserConfiguration(
                        AgentDelegationConfiguration.class,
                        InMemorySubAgentRegistry.class,
                        TaskDelegationTool.class
                )
                .run(context -> assertThat(context).hasSingleBean(TaskDelegationTool.class));
    }

    private TaskDelegationTool tool() {
        return new TaskDelegationTool(new InMemorySubAgentRegistry(List.of(new SimpleTaskSubAgentExecutor())));
    }
}
