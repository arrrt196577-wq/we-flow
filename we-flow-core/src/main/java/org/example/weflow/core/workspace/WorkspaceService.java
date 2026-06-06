package org.example.weflow.core.workspace;

import java.nio.file.Path;

public interface WorkspaceService {

    Path root();

    Path resolve(String path);

    boolean contains(Path path);
}
