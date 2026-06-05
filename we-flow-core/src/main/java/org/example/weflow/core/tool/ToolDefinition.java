package org.example.weflow.core.tool;

public record ToolDefinition(
        String name,
        String description,
        String parametersSchema,
        boolean enabled
) {
}
