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
import org.example.weflow.core.skill.DefaultSkillService;
import org.example.weflow.core.skill.SkillProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillToolsTest {

    @TempDir
    Path skillRoot;

    @Test
    void shouldExposeReadSkillLangChainTool() {
        Set<String> toolNames = Arrays.stream(SkillTools.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(Tool.class))
                .filter(annotation -> annotation != null)
                .map(Tool::name)
                .collect(Collectors.toSet());

        assertThat(toolNames).containsExactly("read_skill");
    }

    @Test
    void readSkillShouldReturnRequestedLineWindow() throws IOException {
        SkillTools tools = tools();
        Files.createDirectories(skillRoot.resolve("public/deep-research"));
        Files.writeString(skillRoot.resolve("public/deep-research/SKILL.md"), "one\ntwo\nthree\n",
                StandardCharsets.UTF_8);

        String result = tools.readSkill("public/deep-research/SKILL.md", 2, 1);

        assertThat(result).contains("status: success");
        assertThat(result).contains("path: public/deep-research/SKILL.md");
        assertThat(result).contains("2 | two");
        assertThat(result).doesNotContain("1 | one");
        assertThat(result).contains("truncated: true");
        assertThat(result).contains("hasMore: true");
        assertThat(result).contains("nextStartLine: 3");
    }

    @Test
    void readSkillShouldRejectDirectory() throws IOException {
        SkillTools tools = tools();
        Files.createDirectories(skillRoot.resolve("public/deep-research"));

        String result = tools.readSkill("public/deep-research", null, null);

        assertThat(result).contains("status: error");
        assertThat(result).contains("code: IS_DIRECTORY");
    }

    @Test
    void readSkillShouldRejectPathOutsideSkillRoot() {
        SkillTools tools = tools();

        String result = tools.readSkill("../secret/SKILL.md", null, null);

        assertThat(result).contains("status: error");
        assertThat(result).contains("code: PATH_OUTSIDE_SKILL_ROOT");
    }

    @Test
    void readSkillShouldRejectMissingFile() {
        SkillTools tools = tools();

        String result = tools.readSkill("public/missing/SKILL.md", null, null);

        assertThat(result).contains("status: error");
        assertThat(result).contains("code: FILE_NOT_FOUND");
    }

    @Test
    void readSkillShouldRejectNonSkillFile() throws IOException {
        SkillTools tools = tools();
        Files.createDirectories(skillRoot.resolve("public/deep-research"));
        Files.writeString(skillRoot.resolve("public/deep-research/README.md"), "readme", StandardCharsets.UTF_8);

        String result = tools.readSkill("public/deep-research/README.md", null, null);

        assertThat(result).contains("status: error");
        assertThat(result).contains("code: UNSUPPORTED_SKILL_FILE");
    }

    @Test
    void readSkillShouldRejectBinaryFile() throws IOException {
        SkillTools tools = tools();
        Files.createDirectories(skillRoot.resolve("public/deep-research"));
        Files.write(skillRoot.resolve("public/deep-research/SKILL.md"), new byte[] {1, 2, 0, 3});

        String result = tools.readSkill("public/deep-research/SKILL.md", null, null);

        assertThat(result).contains("status: error");
        assertThat(result).contains("code: UNSUPPORTED_BINARY_FILE");
    }

    private SkillTools tools() {
        return new SkillTools(new DefaultSkillService(new SkillProperties(skillRoot.toString())));
    }
}
