package org.example.weflow.core.tool;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class InMemoryToolRegistry implements ToolRegistry {

    private final ConcurrentMap<String, ToolDefinition> definitions = new ConcurrentHashMap<>();

    @Override
    public void register(ToolDefinition definition) {
        Objects.requireNonNull(definition, "definition must not be null");
        if (!StringUtils.hasText(definition.name())) {
            throw new IllegalArgumentException("tool name must not be blank");
        }

        ToolDefinition existing = definitions.putIfAbsent(definition.name(), definition);
        if (existing != null) {
            throw new IllegalArgumentException("tool already registered: " + definition.name());
        }
    }

    @Override
    public Optional<ToolDefinition> findByName(String name) {
        return Optional.ofNullable(definitions.get(name));
    }

    @Override
    public List<ToolDefinition> list() {
        return List.copyOf(definitions.values());
    }
}
