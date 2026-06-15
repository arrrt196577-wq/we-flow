package org.example.weflow.agent.subagent;

import java.util.List;
import java.util.Optional;
import org.example.weflow.core.agent.AgentDefinition;
import org.example.weflow.core.agent.AgentExecutor;

public interface SubAgentRegistry {

    Optional<AgentExecutor> findByCode(String code);

    List<AgentDefinition> listDefinitions();
}
