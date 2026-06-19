package org.example.weflow.core.agent;

import java.time.Duration;
import java.util.Objects;

public record AgentRuntimeLimits(
        int maxLoops,
        Duration llmTimeout,
        Duration overallTimeout,
        ToolCallLimit leadToolCallLimit
) {

    public AgentRuntimeLimits {
        if (maxLoops < 1) {
            throw new IllegalArgumentException("maxLoops must be positive");
        }
        Objects.requireNonNull(llmTimeout, "llmTimeout must not be null");
        if (llmTimeout.isZero() || llmTimeout.isNegative()) {
            throw new IllegalArgumentException("llmTimeout must be positive");
        }
        if (overallTimeout != null && (overallTimeout.isZero() || overallTimeout.isNegative())) {
            throw new IllegalArgumentException("overallTimeout must be positive");
        }
    }

    public static AgentRuntimeLimits lead(
            int maxLoops,
            Duration llmTimeout,
            ToolCallLimit leadToolCallLimit
    ) {
        return new AgentRuntimeLimits(maxLoops, llmTimeout, null, leadToolCallLimit);
    }

    public static AgentRuntimeLimits subagent(
            int maxLoops,
            Duration llmTimeout,
            Duration overallTimeout
    ) {
        return new AgentRuntimeLimits(maxLoops, llmTimeout, overallTimeout, null);
    }

    public boolean hasOverallTimeout() {
        return overallTimeout != null;
    }

    public boolean hasLeadToolCallLimit() {
        return leadToolCallLimit != null;
    }

    public record ToolCallLimit(
            int warningThreshold,
            int stopThreshold
    ) {

        public ToolCallLimit {
            if (warningThreshold < 1) {
                throw new IllegalArgumentException("warningThreshold must be positive");
            }
            if (stopThreshold < 1) {
                throw new IllegalArgumentException("stopThreshold must be positive");
            }
            if (warningThreshold >= stopThreshold) {
                throw new IllegalArgumentException("warningThreshold must be less than stopThreshold");
            }
        }
    }
}
