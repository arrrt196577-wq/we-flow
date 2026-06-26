package org.example.weflow.core.skill;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SkillProperties.class)
public class SkillConfiguration {

    @Bean
    public SkillService skillService(SkillProperties properties) {
        return new DefaultSkillService(properties);
    }
}
