package org.example.weflow.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class InMemoryToolRegistryTest {

    private final InMemoryToolRegistry registry = new InMemoryToolRegistry();

    @Test
    void shouldRegisterAndFindToolByName() {
        ToolDefinition definition = toolDefinition("read_file");

        registry.register(definition);

        assertEquals(definition, registry.findByName("read_file").orElseThrow());
    }

    @Test
    void shouldListRegisteredTools() {
        ToolDefinition definition = toolDefinition("list_dir");

        registry.register(definition);

        assertEquals(1, registry.list().size());
        assertTrue(registry.list().contains(definition));
    }

    @Test
    void shouldRejectDuplicateToolName() {
        registry.register(toolDefinition("search_text"));

        assertThrows(IllegalArgumentException.class, () -> registry.register(toolDefinition("search_text")));
    }

    @Test
    void shouldRejectNullDefinition() {
        assertThrows(NullPointerException.class, () -> registry.register(null));
    }

    @Test
    void shouldRejectBlankToolName() {
        ToolDefinition definition = new ToolDefinition(" ", "Read a file", "{}", true);

        assertThrows(IllegalArgumentException.class, () -> registry.register(definition));
    }

    private ToolDefinition toolDefinition(String name) {
        return new ToolDefinition(name, "Test tool", "{}", true);
    }
}
