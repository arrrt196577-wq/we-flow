package org.example.weflow.workflow.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.bsc.langgraph4j.langchain4j.tool.LC4jToolService;
import org.example.weflow.core.tool.WorkspaceFileTools;
import org.example.weflow.core.workspace.DefaultWorkspaceService;
import org.example.weflow.core.workspace.WorkspaceProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
}
