package org.example.weflow.agent.tool;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.extern.slf4j.Slf4j;
import org.example.weflow.core.skill.SkillService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Slf4j
@Component
public class SkillTools implements AgentTool {

    private static final int DEFAULT_MAX_LINES = 200;
    private static final int MAX_LINES_LIMIT = 500;
    private static final int BINARY_SAMPLE_BYTES = 4096;
    private static final String SKILL_FILE_NAME = "SKILL.md";

    private final SkillService skillService;

    public SkillTools(SkillService skillService) {
        this.skillService = skillService;
    }

    @Tool(name = "read_skill", value = "Read a SKILL.md file inside the configured skill root.")
    public String readSkill(
            @P("Relative path inside the skill root, for example public/deep-research/SKILL.md.") String path,
            @P(value = "Start line, 1-based.", required = false) Integer startLine,
            @P(value = "Maximum number of lines to read.", required = false) Integer maxLines
    ) {
        log.info("Tool called: read_skill path={}, startLine={}, maxLines={}", logValue(path), startLine, maxLines);
        if (!StringUtils.hasText(path)) {
            return error("INVALID_ARGUMENT", "path must not be blank");
        }

        Path resolvedPath;
        try {
            resolvedPath = skillService.resolve(path);
        } catch (IllegalArgumentException e) {
            return error("PATH_OUTSIDE_SKILL_ROOT", e.getMessage());
        }

        if (!Files.exists(resolvedPath)) {
            return error("FILE_NOT_FOUND", "file does not exist: " + path);
        }
        if (Files.isDirectory(resolvedPath)) {
            return error("IS_DIRECTORY", "path is a directory: " + path);
        }
        if (!Files.isRegularFile(resolvedPath)) {
            return error("NOT_A_REGULAR_FILE", "path is not a regular file: " + path);
        }
        if (!isSkillFile(resolvedPath)) {
            return error("UNSUPPORTED_SKILL_FILE", "only SKILL.md files can be read: " + path);
        }

        try {
            if (looksBinary(resolvedPath)) {
                return error("UNSUPPORTED_BINARY_FILE", "file appears to be binary: " + path);
            }
            return readTextFile(resolvedPath, clamp(startLine, 1, 1, Integer.MAX_VALUE),
                    clamp(maxLines, DEFAULT_MAX_LINES, 1, MAX_LINES_LIMIT));
        } catch (IOException e) {
            return error("IO_ERROR", e.getMessage());
        }
    }

    private String readTextFile(Path path, int startLine, int maxLines) throws IOException {
        StringBuilder result = new StringBuilder()
                .append("status: success\n")
                .append("path: ").append(relativePath(path)).append('\n')
                .append("startLine: ").append(startLine).append('\n')
                .append("maxLines: ").append(maxLines).append('\n')
                .append("content:\n");

        int currentLine = 0;
        int emittedLines = 0;
        int lastEmittedLine = startLine - 1;
        boolean truncated = false;
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                currentLine++;
                if (currentLine < startLine) {
                    continue;
                }
                if (emittedLines >= maxLines) {
                    truncated = true;
                    break;
                }
                result.append(currentLine).append(" | ").append(line).append('\n');
                emittedLines++;
                lastEmittedLine = currentLine;
            }
        }

        result.append("endLine: ").append(lastEmittedLine).append('\n')
                .append("truncated: ").append(truncated).append('\n')
                .append("hasMore: ").append(truncated).append('\n');
        if (truncated) {
            result.append("nextStartLine: ").append(lastEmittedLine + 1).append('\n');
        }
        return result.toString();
    }

    private boolean looksBinary(Path path) throws IOException {
        byte[] bytes = new byte[BINARY_SAMPLE_BYTES];
        try (InputStream inputStream = Files.newInputStream(path)) {
            int bytesRead = inputStream.read(bytes);
            for (int i = 0; i < bytesRead; i++) {
                if (bytes[i] == 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isSkillFile(Path path) {
        Path fileName = path.getFileName();
        return fileName != null && SKILL_FILE_NAME.equals(fileName.toString());
    }

    private String relativePath(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (normalized.equals(skillService.root())) {
            return ".";
        }
        return skillService.root().relativize(normalized).toString().replace('\\', '/');
    }

    private String logValue(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private int clamp(Integer value, int defaultValue, int min, int max) {
        if (value == null) {
            return defaultValue;
        }
        return Math.max(min, Math.min(max, value));
    }

    private String error(String code, String message) {
        return "status: error\n"
                + "code: " + code + "\n"
                + "message: " + message + "\n";
    }
}
