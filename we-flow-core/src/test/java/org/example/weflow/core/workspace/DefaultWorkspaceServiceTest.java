package org.example.weflow.core.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DefaultWorkspaceServiceTest {

    @TempDir
    Path workspaceRoot;

    @Test
    void shouldReturnNormalizedWorkspaceRoot() {
        DefaultWorkspaceService service = workspaceService(workspaceRoot.resolve(".").toString());

        assertEquals(workspaceRoot.toAbsolutePath().normalize(), service.root());
    }

    @Test
    void shouldResolveRelativePathInsideRoot() {
        DefaultWorkspaceService service = workspaceService(workspaceRoot.toString());

        Path resolvedPath = service.resolve("docs/readme.md");

        assertEquals(workspaceRoot.resolve("docs/readme.md").toAbsolutePath().normalize(), resolvedPath);
        assertTrue(service.contains(resolvedPath));
    }

    @Test
    void shouldAcceptAbsolutePathInsideRoot() {
        DefaultWorkspaceService service = workspaceService(workspaceRoot.toString());
        Path absolutePath = workspaceRoot.resolve("project/a.txt").toAbsolutePath();

        assertEquals(absolutePath.normalize(), service.resolve(absolutePath.toString()));
    }

    @Test
    void shouldRejectParentTraversalOutsideRoot() {
        DefaultWorkspaceService service = workspaceService(workspaceRoot.toString());

        assertThrows(IllegalArgumentException.class, () -> service.resolve("../secret.txt"));
    }

    @Test
    void shouldRejectAbsolutePathOutsideRoot() throws IOException {
        Path outsideDirectory = Files.createTempDirectory("we-flow-outside");
        DefaultWorkspaceService service = workspaceService(workspaceRoot.toString());

        assertThrows(IllegalArgumentException.class, () -> service.resolve(outsideDirectory.resolve("a.txt").toString()));
        assertFalse(service.contains(outsideDirectory));
    }

    @Test
    void shouldRejectBlankPath() {
        DefaultWorkspaceService service = workspaceService(workspaceRoot.toString());

        assertThrows(IllegalArgumentException.class, () -> service.resolve(" "));
    }

    @Test
    void shouldRejectBlankRootPath() {
        assertThrows(IllegalStateException.class, () -> workspaceService(" "));
    }

    @Test
    void shouldRejectMissingRootDirectory() {
        Path missingDirectory = workspaceRoot.resolve("missing");

        assertThrows(IllegalStateException.class, () -> workspaceService(missingDirectory.toString()));
    }

    @Test
    void shouldRejectRootPathThatIsNotDirectory() throws IOException {
        Path file = Files.createFile(workspaceRoot.resolve("file.txt"));

        assertThrows(IllegalStateException.class, () -> workspaceService(file.toString()));
    }

    private DefaultWorkspaceService workspaceService(String rootPath) {
        return new DefaultWorkspaceService(new WorkspaceProperties(rootPath));
    }
}
