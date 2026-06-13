package org.example.weflow.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;

import dev.langchain4j.agent.tool.Tool;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.example.weflow.core.workspace.DefaultWorkspaceService;
import org.example.weflow.core.workspace.WorkspaceProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceFileToolsTest {

    @TempDir
    Path workspaceRoot;

    @Test
    void shouldExposeThreeLangChainTools() {
        Set<String> toolNames = Arrays.stream(WorkspaceFileTools.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(Tool.class))
                .filter(annotation -> annotation != null)
                .map(Tool::name)
                .collect(Collectors.toSet());

        assertThat(toolNames).containsExactlyInAnyOrder("find_files", "read_file", "list_dir");
    }

    @Test
    void readFileShouldReturnRequestedLineWindow() throws IOException {
        WorkspaceFileTools tools = tools();
        Files.createDirectories(workspaceRoot.resolve("docs"));
        Files.writeString(workspaceRoot.resolve("docs/readme.md"), "one\ntwo\nthree\n", StandardCharsets.UTF_8);

        String result = tools.readFile("docs/readme.md", 2, 1);

        assertThat(result).contains("status: success");
        assertThat(result).contains("path: docs/readme.md");
        assertThat(result).contains("2 | two");
        assertThat(result).doesNotContain("1 | one");
        assertThat(result).contains("truncated: true");
        assertThat(result).contains("hasMore: true");
        assertThat(result).contains("nextStartLine: 3");
    }

    @Test
    void readFileShouldRejectDirectory() throws IOException {
        WorkspaceFileTools tools = tools();
        Files.createDirectories(workspaceRoot.resolve("docs"));

        String result = tools.readFile("docs", null, null);

        assertThat(result).contains("status: error");
        assertThat(result).contains("code: IS_DIRECTORY");
    }

    @Test
    void readFileShouldRejectPathOutsideWorkspace() {
        WorkspaceFileTools tools = tools();

        String result = tools.readFile("../secret.txt", null, null);

        assertThat(result).contains("status: error");
        assertThat(result).contains("code: PATH_OUTSIDE_WORKSPACE");
    }

    @Test
    void findFilesShouldMatchPartialPathOrFileName() throws IOException {
        WorkspaceFileTools tools = tools();
        Files.createDirectories(workspaceRoot.resolve("src/main/java"));
        Files.writeString(workspaceRoot.resolve("src/main/java/ChatServiceImpl.java"), "class ChatServiceImpl {}",
                StandardCharsets.UTF_8);
        Files.writeString(workspaceRoot.resolve("src/main/java/Other.java"), "class Other {}", StandardCharsets.UTF_8);

        String result = tools.findFiles("ChatService", 10);

        assertThat(result).contains("status: success");
        assertThat(result).contains("- src/main/java/ChatServiceImpl.java");
        assertThat(result).doesNotContain("- src/main/java/Other.java");
    }

    @Test
    void listDirShouldReturnDirectChildrenOnly() throws IOException {
        WorkspaceFileTools tools = tools();
        Files.createDirectories(workspaceRoot.resolve("src/main"));
        Files.writeString(workspaceRoot.resolve("src/App.java"), "class App {}", StandardCharsets.UTF_8);
        Files.writeString(workspaceRoot.resolve("src/main/Nested.java"), "class Nested {}", StandardCharsets.UTF_8);

        String result = tools.listDir("src", 10);

        assertThat(result).contains("status: success");
        assertThat(result).contains("- dir src/main");
        assertThat(result).contains("- file src/App.java");
        assertThat(result).doesNotContain("src/main/Nested.java");
    }

    @Test
    void listDirShouldUseWorkspaceRootWhenPathIsBlank() throws IOException {
        WorkspaceFileTools tools = tools();
        Files.writeString(workspaceRoot.resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8);

        String result = tools.listDir(" ", 10);

        assertThat(result).contains("path: .");
        assertThat(result).contains("- file pom.xml");
    }

    private WorkspaceFileTools tools() {
        return new WorkspaceFileTools(new DefaultWorkspaceService(new WorkspaceProperties(workspaceRoot.toString())));
    }
}
