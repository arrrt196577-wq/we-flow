package org.example.weflow.core.agent;

public interface AgentExecutor {

    AgentDefinition definition();

    AgentResult execute(AgentTask task, AgentContext context);
}
