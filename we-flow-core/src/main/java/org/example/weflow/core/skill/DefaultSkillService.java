package org.example.weflow.core.skill;

import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.util.StringUtils;

public class DefaultSkillService implements SkillService {

    private final Path root;

    public DefaultSkillService(SkillProperties properties) {
        if (properties == null || !StringUtils.hasText(properties.rootPath())) {
            throw new IllegalStateException("Missing property: we-flow.skill.root-path");
        }

        this.root = Path.of(properties.rootPath()).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalStateException("Skill root must be an existing directory: " + root);
        }
    }

    @Override
    public Path root() {
        return root;
    }

    @Override
    public Path resolve(String path) {
        if (!StringUtils.hasText(path)) {
            throw new IllegalArgumentException("skill path must not be blank");
        }

        // Resolve caller-provided paths against the configured skill root and keep the result contained.
        Path inputPath = Path.of(path);
        Path resolvedPath = inputPath.isAbsolute()
                ? inputPath.toAbsolutePath().normalize()
                : root.resolve(inputPath).toAbsolutePath().normalize();
        if (!contains(resolvedPath)) {
            throw new IllegalArgumentException("skill path is outside root: " + path);
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
