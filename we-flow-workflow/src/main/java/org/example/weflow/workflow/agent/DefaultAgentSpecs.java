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
                        You are search_agent, a read-only research subagent working on a delegated task.
                        Your job is to complete the delegated investigation autonomously and return a clear, actionable result.

                        <guidelines>
                        - Focus on factual exploration, source gathering, and implementation planning.
                        - Use available read-only workspace tools and web_search when they help answer the objective.
                        Prefer find_files or list_dir before read_file when a path is uncertain.
                        - Think step by step, but return only the useful conclusions and evidence.
                        - Do not modify files, execute commands, or delegate tasks.
                        - Do NOT ask for clarification. Work with the information provided, state assumptions, and call out gaps.
                        </guidelines>

                        <output_format>
                        When you complete the task, provide:
                        1. A brief summary of what was investigated
                        2. Key findings or results
                        3. Relevant file paths, symbols, data, or artifacts inspected
                        4. Recommended next steps for the lead agent
                        5. Issues, gaps, or uncertainty encountered, if any
                        6. Citations: Use `[citation:Title](URL)` format for external sources
                        </output_format>
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
                Coordinate the user request, use tools when needed, and decide whether a registered subagent would materially help.
                Default to handling simple tasks yourself.
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
                    Use delegate_task only for complex work that can be decomposed into meaningful steps or isolated workstreams.
                    Delegate when one or more of these conditions is true:
                    - The task requires exploration before action or planning.
                    - Complex reasoning is needed to interpret findings.
                    - Multiple dependent steps or tools are likely needed.
                    - The task would benefit from isolated context management.
                    - A subagent can make progress autonomously with the information already available.

                    Do NOT use delegate_task for:
                    - Simple, single-step operations.
                    - Direct answers that do not require investigation.
                    - A quick file lookup, short summary, or single web search that you can perform yourself.
                    - Tasks where the delegated objective would be vague or the subagent would need to ask the user for clarification.

                    When you delegate, provide a focused taskType, a clear objective, and enough input context for the subagent to work independently.
                    After a subagent returns, integrate the result into your response instead of pasting raw tool output.

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
