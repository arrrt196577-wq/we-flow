package org.example.weflow.agent.tool;

/**
 * Marker interface for Spring-managed tool beans that should be exposed to agents.
 *
 * <p>{@code AgentToolConfiguration} injects all beans of this type and lets
 * LangChain4j scan their {@code @Tool} methods.
 */
public interface AgentTool {
}
