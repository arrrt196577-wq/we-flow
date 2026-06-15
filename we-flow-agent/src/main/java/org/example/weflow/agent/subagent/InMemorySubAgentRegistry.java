package org.example.weflow.agent.subagent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.example.weflow.core.agent.AgentDefinition;
import org.example.weflow.core.agent.AgentExecutor;
import org.example.weflow.core.agent.AgentType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class InMemorySubAgentRegistry implements SubAgentRegistry {

    private final Map<String, AgentExecutor> executors;

    public InMemorySubAgentRegistry(List<AgentExecutor> agentExecutors) {
        Map<String, AgentExecutor> registeredExecutors = new LinkedHashMap<>();
        for (AgentExecutor executor : agentExecutors) {
            AgentDefinition definition = executor.definition();
            if (definition == null
                    || definition.type() != AgentType.SUB
                    || !definition.enabled()) {
                continue;
            }
            if (!StringUtils.hasText(definition.code())) {
                throw new IllegalArgumentException("subagent code must not be blank");
            }
            AgentExecutor existing = registeredExecutors.putIfAbsent(definition.code(), executor);
            if (existing != null) {
                throw new IllegalArgumentException("subagent already registered: " + definition.code());
            }
        }
        this.executors = Map.copyOf(registeredExecutors);
    }

    @Override
    public Optional<AgentExecutor> findByCode(String code) {
        if (!StringUtils.hasText(code)) {
            return Optional.empty();
        }
        return Optional.ofNullable(executors.get(code.trim()));
    }

    @Override
    public List<AgentDefinition> listDefinitions() {
        return executors.values().stream()
                .map(AgentExecutor::definition)
                .toList();
    }
}
