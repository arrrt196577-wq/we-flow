package org.example.weflow.workflow.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "we-flow.agent")
public record AgentRuntimeProperties(
        MaxToolIterations maxToolIterations
) {

    public AgentRuntimeProperties {
        maxToolIterations = maxToolIterations == null ? new MaxToolIterations(null, null) : maxToolIterations;
    }

    public int leadMaxToolIterations() {
        return maxToolIterations.leadOrDefault();
    }

    public int searchMaxToolIterations() {
        return maxToolIterations.searchOrDefault();
    }

    public record MaxToolIterations(
            Integer lead,
            Integer search
    ) {

        private int leadOrDefault() {
            return positiveOrDefault(lead, DefaultAgentSpecs.DEFAULT_LEAD_MAX_TOOL_ITERATIONS, "lead");
        }

        private int searchOrDefault() {
            return positiveOrDefault(search, DefaultAgentSpecs.DEFAULT_SEARCH_MAX_TOOL_ITERATIONS, "search");
        }

        private static int positiveOrDefault(Integer value, int defaultValue, String name) {
            if (value == null) {
                return defaultValue;
            }
            if (value < 1) {
                throw new IllegalArgumentException("we-flow.agent.max-tool-iterations." + name + " must be positive");
            }
            return value;
        }
    }
}
