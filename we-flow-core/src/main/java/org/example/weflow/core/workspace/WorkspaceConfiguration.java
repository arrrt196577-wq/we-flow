package org.example.weflow.core.workspace;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WorkspaceProperties.class)
public class WorkspaceConfiguration {

    @Bean
    public WorkspaceService workspaceService(WorkspaceProperties properties) {
        return new DefaultWorkspaceService(properties);
    }
}
