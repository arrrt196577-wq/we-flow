package org.example.weflow.core.tool;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.example.weflow.core.workspace.WorkspaceService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class WorkspaceFileTools implements AgentTool {

    private static final int DEFAULT_MAX_RESULTS = 20;
    private static final int MAX_RESULTS_LIMIT = 50;
    private static final int DEFAULT_MAX_LINES = 200;
    private static final int MAX_LINES_LIMIT = 500;
    private static final int DEFAULT_MAX_ENTRIES = 100;
    private static final int MAX_ENTRIES_LIMIT = 200;
    private static final int BINARY_SAMPLE_BYTES = 4096;
    private static final List<String> IGNORED_DIRECTORY_NAMES = List.of(".git", ".idea", "target");

    private final WorkspaceService workspaceService;

    public WorkspaceFileTools(WorkspaceService workspaceService) {
        this.workspaceService = workspaceService;
    }

    @Tool(name = "find_files", value = "Find files in the workspace by partial path or file name.")
    public String findFiles(
            @P("Partial file name or relative path to find.") String query,
            @P(value = "Maximum number of matches to return.", required = false) Integer maxResults
    ) {
        if (!StringUtils.hasText(query)) {
            return error("INVALID_ARGUMENT", "query must not be blank");
        }

        int limit = clamp(maxResults, DEFAULT_MAX_RESULTS, 1, MAX_RESULTS_LIMIT);
        String normalizedQuery = normalizeForMatch(query);
        StringBuilder result = new StringBuilder()
                .append("status: success\n")
                .append("query: ").append(query.trim()).append('\n')
                .append("maxResults: ").append(limit).append('\n')
                .append("matches:\n");

        try (Stream<Path> paths = Files.walk(workspaceService.root())) {
            List<Path> matches = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> !isIgnored(path))
                    .filter(path -> normalizeForMatch(relativePath(path)).contains(normalizedQuery)
                            || normalizeForMatch(path.getFileName().toString()).contains(normalizedQuery))
                    .sorted(Comparator.comparing(this::relativePath))
                    .limit(limit)
                    .toList();

            if (matches.isEmpty()) {
                result.append("(none)\n");
            } else {
                for (Path match : matches) {
                    result.append("- ").append(relativePath(match)).append('\n');
                }
            }
            return result.toString();
        } catch (IOException e) {
            return error("IO_ERROR", e.getMessage());
        }
    }

    @Tool(name = "read_file", value = "Read a text file inside the workspace.")
    public String readFile(
            @P("Relative path inside the workspace.") String path,
            @P(value = "Start line, 1-based.", required = false) Integer startLine,
            @P(value = "Maximum number of lines to read.", required = false) Integer maxLines
    ) {
        if (!StringUtils.hasText(path)) {
            return error("INVALID_ARGUMENT", "path must not be blank");
        }

        Path resolvedPath;
        try {
            resolvedPath = workspaceService.resolve(path);
        } catch (IllegalArgumentException e) {
            return error("PATH_OUTSIDE_WORKSPACE", e.getMessage());
        }

        if (!Files.exists(resolvedPath)) {
            return error("FILE_NOT_FOUND", "file does not exist: " + path);
        }
        if (Files.isDirectory(resolvedPath)) {
            return error("IS_DIRECTORY", "path is a directory; use list_dir instead: " + path);
        }

        if (!Files.isRegularFile(resolvedPath)) {
            return error("NOT_A_REGULAR_FILE", "path is not a regular file: " + path);
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

    @Tool(name = "list_dir", value = "List direct children of a directory inside the workspace.")
    public String listDir(
            @P(value = "Relative directory path inside the workspace. Use . for root.", required = false) String path,
            @P(value = "Maximum number of entries to return.", required = false) Integer maxEntries
    ) {
        String requestedPath = StringUtils.hasText(path) ? path : ".";
        Path resolvedPath;
        try {
            resolvedPath = workspaceService.resolve(requestedPath);
        } catch (IllegalArgumentException e) {
            return error("PATH_OUTSIDE_WORKSPACE", e.getMessage());
        }

        if (!Files.exists(resolvedPath)) {
            return error("DIRECTORY_NOT_FOUND", "directory does not exist: " + requestedPath);
        }
        if (!Files.isDirectory(resolvedPath)) {
            return error("NOT_A_DIRECTORY", "path is not a directory: " + requestedPath);
        }

        int limit = clamp(maxEntries, DEFAULT_MAX_ENTRIES, 1, MAX_ENTRIES_LIMIT);
        StringBuilder result = new StringBuilder()
                .append("status: success\n")
                .append("path: ").append(relativePath(resolvedPath)).append('\n')
                .append("maxEntries: ").append(limit).append('\n')
                .append("entries:\n");

        try (Stream<Path> entries = Files.list(resolvedPath)) {
            List<Path> children = entries
                    .filter(pathEntry -> !isIgnored(pathEntry))
                    .sorted(Comparator
                            .comparing((Path pathEntry) -> !Files.isDirectory(pathEntry))
                            .thenComparing(this::relativePath))
                    .limit(limit)
                    .toList();

            if (children.isEmpty()) {
                result.append("(empty)\n");
            } else {
                for (Path child : children) {
                    result.append("- ")
                            .append(Files.isDirectory(child) ? "dir " : "file ")
                            .append(relativePath(child))
                            .append('\n');
                }
            }
            return result.toString();
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

    private boolean isIgnored(Path path) {
        Path relative = workspaceService.root().relativize(path.toAbsolutePath().normalize());
        for (Path part : relative) {
            if (IGNORED_DIRECTORY_NAMES.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }

    private String relativePath(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (normalized.equals(workspaceService.root())) {
            return ".";
        }
        return workspaceService.root().relativize(normalized).toString().replace('\\', '/');
    }

    private String normalizeForMatch(String value) {
        return value.replace('\\', '/').trim().toLowerCase(Locale.ROOT);
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
