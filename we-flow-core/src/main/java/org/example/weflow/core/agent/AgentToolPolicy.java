package org.example.weflow.core.agent;

import java.util.Set;

public record AgentToolPolicy(
        Mode mode,
        Set<String> toolNames
) {

    public enum Mode {
        ALL,
        ONLY,
        NONE
    }

    public AgentToolPolicy {
        mode = mode == null ? Mode.ALL : mode;
        toolNames = toolNames == null ? Set.of() : Set.copyOf(toolNames);
    }

    public static AgentToolPolicy all() {
        return new AgentToolPolicy(Mode.ALL, Set.of());
    }

    public static AgentToolPolicy only(Set<String> toolNames) {
        return new AgentToolPolicy(Mode.ONLY, toolNames);
    }

    public static AgentToolPolicy none() {
        return new AgentToolPolicy(Mode.NONE, Set.of());
    }

    public boolean allows(String toolName) {
        return switch (mode) {
            case ALL -> true;
            case ONLY -> toolNames.contains(toolName);
            case NONE -> false;
        };
    }
}
