package org.example.weflow.core.tool;

import java.util.List;
import java.util.Optional;

/**
 * 应用内部工具注册表
 */
public interface ToolRegistry {

    void register(ToolDefinition definition);

    Optional<ToolDefinition> findByName(String name);

    List<ToolDefinition> list();
}
