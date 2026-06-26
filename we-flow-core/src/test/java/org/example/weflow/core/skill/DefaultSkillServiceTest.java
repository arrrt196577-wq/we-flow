package org.example.weflow.core.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DefaultSkillServiceTest {

    @TempDir
    Path skillRoot;

    @Test
    void shouldReturnNormalizedSkillRoot() {
        DefaultSkillService service = skillService(skillRoot.resolve(".").toString());

        assertEquals(skillRoot.toAbsolutePath().normalize(), service.root());
    }

    @Test
    void shouldResolveRelativeSkillPathInsideRoot() throws IOException {
        Files.createDirectories(skillRoot.resolve("public/deep-research"));
        DefaultSkillService service = skillService(skillRoot.toString());

        Path resolvedPath = service.resolve("public/deep-research/SKILL.md");

        assertEquals(skillRoot.resolve("public/deep-research/SKILL.md").toAbsolutePath().normalize(), resolvedPath);
        assertTrue(service.contains(resolvedPath));
    }

    @Test
    void shouldAcceptAbsolutePathInsideRoot() throws IOException {
        Files.createDirectories(skillRoot.resolve("public/deep-research"));
        DefaultSkillService service = skillService(skillRoot.toString());
        Path absolutePath = skillRoot.resolve("public/deep-research/SKILL.md").toAbsolutePath();

        assertEquals(absolutePath.normalize(), service.resolve(absolutePath.toString()));
    }

    @Test
    void shouldRejectParentTraversalOutsideRoot() {
        DefaultSkillService service = skillService(skillRoot.toString());

        assertThrows(IllegalArgumentException.class, () -> service.resolve("../secret.md"));
    }

    @Test
    void shouldRejectAbsolutePathOutsideRoot() throws IOException {
        Path outsideDirectory = Files.createTempDirectory("we-flow-skill-outside");
        DefaultSkillService service = skillService(skillRoot.toString());

        assertThrows(IllegalArgumentException.class,
                () -> service.resolve(outsideDirectory.resolve("SKILL.md").toString()));
        assertFalse(service.contains(outsideDirectory));
    }

    @Test
    void shouldRejectBlankPath() {
        DefaultSkillService service = skillService(skillRoot.toString());

        assertThrows(IllegalArgumentException.class, () -> service.resolve(" "));
    }

    @Test
    void shouldRejectBlankRootPath() {
        assertThrows(IllegalStateException.class, () -> skillService(" "));
    }

    @Test
    void shouldRejectMissingRootDirectory() {
        Path missingDirectory = skillRoot.resolve("missing");

        assertThrows(IllegalStateException.class, () -> skillService(missingDirectory.toString()));
    }

    @Test
    void shouldRejectRootPathThatIsNotDirectory() throws IOException {
        Path file = Files.createFile(skillRoot.resolve("file.txt"));

        assertThrows(IllegalStateException.class, () -> skillService(file.toString()));
    }

    private DefaultSkillService skillService(String rootPath) {
        return new DefaultSkillService(new SkillProperties(rootPath));
    }
}
