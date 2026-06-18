package org.example.weflow.workflow.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.bsc.langgraph4j.langchain4j.tool.LC4jToolService;
import org.example.weflow.agent.config.AgentDelegationConfiguration;
import org.example.weflow.agent.subagent.InMemorySubAgentRegistry;
import org.example.weflow.agent.subagent.SimpleTaskSubAgentExecutor;
import org.example.weflow.agent.tool.TaskDelegationTool;
import org.example.weflow.agent.tool.WebSearchTools;
import org.example.weflow.agent.tool.WorkspaceFileTools;
import org.example.weflow.core.agent.AgentExecutor;
import org.example.weflow.core.workspace.DefaultWorkspaceService;
import org.example.weflow.core.workspace.WorkspaceProperties;
import org.example.weflow.integration.search.WebSearchClient;
import org.example.weflow.integration.search.WebSearchConfiguration;
import org.example.weflow.integration.search.WebSearchProperties;
import org.example.weflow.integration.search.WebSearchResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AgentToolConfigurationTest {

    @TempDir
    Path workspaceRoot;

    @Test
    void shouldRegisterWorkspaceFileToolsAsLangChainToolSpecifications() {
        WorkspaceFileTools fileTools = new WorkspaceFileTools(
                new DefaultWorkspaceService(new WorkspaceProperties(workspaceRoot.toString())));
        LC4jToolService toolService = new AgentToolConfiguration().lc4jToolService(List.of(fileTools));

        Set<String> toolNames = toolService.toolSpecifications().stream()
                .map(specification -> specification.name())
                .collect(Collectors.toSet());

        assertThat(toolNames).containsExactlyInAnyOrder("find_files", "read_file", "list_dir");
    }

    @Test
    void shouldNotRegisterWebSearchToolWhenSearchIsDisabledByDefault() {
        new ApplicationContextRunner()
                .withBean(WebSearchClient.class, () -> request -> new WebSearchResponse(request.query(), List.of()))
                .withUserConfiguration(AgentToolConfiguration.class, WebSearchTools.class)
                .run(context -> {
                    assertThat(context).doesNotHaveBean(WebSearchTools.class);
                    assertThat(toolNames(context.getBean(LC4jToolService.class))).doesNotContain("web_search");
                });
    }

    @Test
    void shouldRegisterWebSearchToolWhenSearchIsEnabled() {
        new ApplicationContextRunner()
                .withPropertyValues("we-flow.search.enabled=true")
                .withBean(WebSearchClient.class, () -> request -> new WebSearchResponse(request.query(), List.of()))
                .withBean(WebSearchProperties.class, () -> new WebSearchProperties(
                        true,
                        "duckduckgo",
                        5,
                        Duration.ofSeconds(10),
                        "wt-wt",
                        "moderate",
                        null))
                .withUserConfiguration(AgentToolConfiguration.class, WebSearchTools.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(WebSearchTools.class);
                    assertThat(toolNames(context.getBean(LC4jToolService.class))).contains("web_search");
                });
    }

    @Test
    void shouldRegisterWebSearchToolWithRealSearchConfigurationWhenSearchIsEnabled() {
        new ApplicationContextRunner()
                .withPropertyValues("we-flow.search.enabled=true")
                .withUserConfiguration(WebSearchConfiguration.class, AgentToolConfiguration.class, WebSearchTools.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(WebSearchClient.class);
                    assertThat(context).hasSingleBean(WebSearchTools.class);
                    assertThat(toolNames(context.getBean(LC4jToolService.class))).contains("web_search");
                });
    }

    @Test
    void shouldRegisterTaskDelegationToolWhenDelegationIsEnabled() {
        new ApplicationContextRunner()
                .withPropertyValues("we-flow.agent.delegation.enabled=true")
                .withBean(AgentExecutor.class, SimpleTaskSubAgentExecutor::new)
                .withUserConfiguration(
                        AgentDelegationConfiguration.class,
                        InMemorySubAgentRegistry.class,
                        AgentToolConfiguration.class,
                        TaskDelegationTool.class
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(TaskDelegationTool.class);
                    assertThat(toolNames(context.getBean(LC4jToolService.class))).contains("delegate_task");
                });
    }

    private Set<String> toolNames(LC4jToolService toolService) {
        return toolService.toolSpecifications().stream()
                .map(specification -> specification.name())
                .collect(Collectors.toSet());
    }
}
