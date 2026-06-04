package org.example.weflow.infrastructure.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan("org.example.weflow.infrastructure.persistence.mapper")
public class MybatisPlusConfig {
}
