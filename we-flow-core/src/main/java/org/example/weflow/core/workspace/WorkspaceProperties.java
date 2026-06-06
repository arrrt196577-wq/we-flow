package org.example.weflow.core.workspace;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "we-flow.workspace")
public record WorkspaceProperties(
        String rootPath
) {
}
