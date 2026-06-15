package org.example.weflow.agent.subagent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.example.weflow.core.agent.AgentContext;
import org.example.weflow.core.agent.AgentDefinition;
import org.example.weflow.core.agent.AgentExecutor;
import org.example.weflow.core.agent.AgentResult;
import org.example.weflow.core.agent.AgentTask;
import org.example.weflow.core.agent.AgentType;
import org.junit.jupiter.api.Test;

class InMemorySubAgentRegistryTest {

    @Test
    void registersEnabledSubAgentsOnly() {
        InMemorySubAgentRegistry registry = new InMemorySubAgentRegistry(List.of(
                new SimpleTaskSubAgentExecutor(),
                executor("disabled_subagent", AgentType.SUB, false),
                executor("lead_agent", AgentType.LEAD, true)
        ));

        assertThat(registry.findByCode(SimpleTaskSubAgentExecutor.CODE)).isPresent();
        assertThat(registry.findByCode("disabled_subagent")).isEmpty();
        assertThat(registry.findByCode("lead_agent")).isEmpty();
        assertThat(registry.listDefinitions())
                .extracting(AgentDefinition::code)
                .containsExactly(SimpleTaskSubAgentExecutor.CODE);
    }

    private AgentExecutor executor(String code, AgentType type, boolean enabled) {
        return new AgentExecutor() {
            @Override
            public AgentDefinition definition() {
                return new AgentDefinition(code, code, type, "test agent", enabled);
            }

            @Override
            public AgentResult execute(AgentTask task, AgentContext context) {
                return AgentResult.success(task.taskId(), code, "ok");
            }
        };
    }
}
