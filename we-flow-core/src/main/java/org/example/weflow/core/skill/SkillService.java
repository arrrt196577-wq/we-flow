package org.example.weflow.core.skill;

import java.nio.file.Path;

public interface SkillService {

    Path root();

    Path resolve(String path);

    boolean contains(Path path);
}
