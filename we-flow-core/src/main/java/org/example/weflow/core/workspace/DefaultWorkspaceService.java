package org.example.weflow.core.workspace;

import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.util.StringUtils;

public class DefaultWorkspaceService implements WorkspaceService {

    private final Path root;

    public DefaultWorkspaceService(WorkspaceProperties properties) {
        if (properties == null || !StringUtils.hasText(properties.rootPath())) {
            throw new IllegalStateException("Missing property: we-flow.workspace.root-path");
        }

        this.root = Path.of(properties.rootPath()).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalStateException("Workspace root must be an existing directory: " + root);
        }
    }

    @Override
    public Path root() {
        return root;
    }

    @Override
    public Path resolve(String path) {
        if (!StringUtils.hasText(path)) {
            throw new IllegalArgumentException("workspace path must not be blank");
        }

        // String -> Path
        Path inputPath = Path.of(path);
        // .normalize() -> 解析所有的./和../
        Path resolvedPath = inputPath.isAbsolute() // 是否是绝对路径
                ? inputPath.toAbsolutePath().normalize()
                : root.resolve(inputPath).toAbsolutePath().normalize(); // 拼接到root下

        // 判断解析后的path是否在workspace下
        if (!contains(resolvedPath)) {
            throw new IllegalArgumentException("workspace path is outside root: " + path);
        }
        return resolvedPath;
    }

    @Override
    public boolean contains(Path path) {
        if (path == null) {
            return false;
        }
        return path.toAbsolutePath().normalize().startsWith(root);
    }
}
