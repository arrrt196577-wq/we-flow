package org.example.weflow.workflow.agent;

import java.util.Collection;
import java.util.Comparator;
import java.util.Set;
import java.util.stream.Collectors;
import org.example.weflow.core.agent.AgentDefinition;
import org.example.weflow.core.agent.AgentSpec;
import org.example.weflow.core.agent.AgentToolPolicy;
import org.example.weflow.core.agent.AgentType;

public final class DefaultAgentSpecs {

    public static final String LEAD_AGENT_CODE = "lead_agent";
    public static final String SEARCH_AGENT_CODE = "search_agent";
    public static final String IMPLEMENT_AGENT_CODE = "implement_agent";

    private static final int LEAD_MAX_TOOL_ITERATIONS = 8;
    private static final int SEARCH_MAX_TOOL_ITERATIONS = 6;
    private static final String WEB_SEARCH_TOOL = "web_search";
    private static final String DELEGATE_TASK_TOOL = "delegate_task";
    private static final Set<String> SEARCH_AGENT_TOOLS = Set.of(
            WEB_SEARCH_TOOL,
            "find_files",
            "read_file",
            "list_dir"
    );

    private DefaultAgentSpecs() {
    }

    public static AgentSpec leadAgentSpec(Collection<AgentDefinition> subAgentDefinitions, Set<String> toolNames) {
        return new AgentSpec(
                new AgentDefinition(
                        LEAD_AGENT_CODE,
                        "Lead Agent",
                        AgentType.LEAD,
                        "Coordinates user requests, tools, and subagents.",
                        true
                ),
                leadSystemPrompt(subAgentDefinitions, toolNames),
                AgentToolPolicy.all(),
                LEAD_MAX_TOOL_ITERATIONS
        );
    }

    public static AgentSpec searchAgentSpec() {
        return new AgentSpec(
                searchAgentDefinition(),
                """
                        You are search_agent.
                        Research complex tasks by using read-only workspace inspection and web search when those tools are available.
                        Prefer find_files or list_dir before read_file when a path is uncertain.
                        Do not modify files, execute commands, or delegate tasks.
                        Return concise, factual results with evidence paths or source links when available.
                        """,
                AgentToolPolicy.only(SEARCH_AGENT_TOOLS),
                SEARCH_MAX_TOOL_ITERATIONS
        );
    }

    public static AgentDefinition searchAgentDefinition() {
        return new AgentDefinition(
                SEARCH_AGENT_CODE,
                "Search Agent",
                AgentType.SUB,
                "Researches web and workspace files to produce factual implementation plans.",
                true
        );
    }

    public static AgentDefinition implementAgentDefinition() {
        return new AgentDefinition(
                IMPLEMENT_AGENT_CODE,
                "Implement Agent",
                AgentType.SUB,
                "Placeholder for future file-writing and command-execution implementation work.",
                true
        );
    }

    private static String leadSystemPrompt(Collection<AgentDefinition> subAgentDefinitions, Set<String> toolNames) {
        StringBuilder prompt = new StringBuilder("""
                You are lead_agent.
                Coordinate the user request, use tools when needed, and delegate independent work to a registered subagent when useful.
                If the user references an uncertain path or file name and file tools are available, call find_files or list_dir before read_file.
                When read_file returns hasMore: true and you still need more content, call read_file again with nextStartLine.
                """);

        if (toolNames.contains(WEB_SEARCH_TOOL)) {
            prompt.append("""
                    Use web_search for current or external public information such as news, versions, prices, policy, or facts not already known from the conversation.
                    When you use web_search, answer only from the returned results and include source links for claims based on those results.
                    If web_search returns no results, say that no reliable search results were found and do not invent sources.
                    """);
        } else {
            prompt.append("""
                    Web search is not available in this session.
                    If the user asks you to search the web, browse, look up current information, or verify latest external facts, say that search is not enabled and do not invent sources or citations.
                    """);
        }

        if (toolNames.contains(DELEGATE_TASK_TOOL)) {
            prompt.append("""
                    Use delegate_task only when the user request contains an independent task that should be handled by a subagent.
                    Available subagents:
                    """);
            String subAgents = subAgentDefinitions.stream()
                    .sorted(Comparator.comparing(AgentDefinition::code))
                    .map(definition -> "- " + definition.code() + ": " + definition.description())
                    .collect(Collectors.joining("\n"));
            prompt.append(subAgents.isBlank() ? "- (none)" : subAgents);
            prompt.append("""

                    Do not invent other subagent codes.
                    """);
        }

        return prompt.toString();
    }
}
