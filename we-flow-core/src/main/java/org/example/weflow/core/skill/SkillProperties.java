package org.example.weflow.core.skill;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "we-flow.skill")
public record SkillProperties(
        String rootPath
) {
}
