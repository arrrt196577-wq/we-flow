package org.example.weflow.workflow.agent;

import java.time.Duration;
import org.example.weflow.core.agent.AgentRuntimeLimits;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "we-flow.agent")
public record AgentRuntimeProperties(
        MaxLoops maxLoops,
        Duration llmTimeout,
        Duration subagentTimeout,
        LeadToolCallLimit leadToolCallLimit
) {

    public AgentRuntimeProperties {
        maxLoops = maxLoops == null ? new MaxLoops(null, null) : maxLoops;
        leadToolCallLimit = leadToolCallLimit == null
                ? new LeadToolCallLimit(null, null)
                : leadToolCallLimit;
    }

    public AgentRuntimeLimits leadRuntimeLimits() {
        return AgentRuntimeLimits.lead(
                positiveOrDefault(maxLoops.lead(), DefaultAgentSpecs.DEFAULT_LEAD_MAX_LOOPS,
                        "we-flow.agent.max-loops.lead"),
                positiveDurationOrDefault(llmTimeout, DefaultAgentSpecs.DEFAULT_LLM_TIMEOUT,
                        "we-flow.agent.llm-timeout"),
                new AgentRuntimeLimits.ToolCallLimit(
                        positiveOrDefault(leadToolCallLimit.warningThreshold(),
                                DefaultAgentSpecs.DEFAULT_LEAD_TOOL_WARNING_THRESHOLD,
                                "we-flow.agent.lead-tool-call-limit.warning-threshold"),
                        positiveOrDefault(leadToolCallLimit.stopThreshold(),
                                DefaultAgentSpecs.DEFAULT_LEAD_TOOL_STOP_THRESHOLD,
                                "we-flow.agent.lead-tool-call-limit.stop-threshold")
                )
        );
    }

    public AgentRuntimeLimits subagentRuntimeLimits() {
        return AgentRuntimeLimits.subagent(
                positiveOrDefault(maxLoops.subagent(), DefaultAgentSpecs.DEFAULT_SUBAGENT_MAX_LOOPS,
                        "we-flow.agent.max-loops.subagent"),
                positiveDurationOrDefault(llmTimeout, DefaultAgentSpecs.DEFAULT_LLM_TIMEOUT,
                        "we-flow.agent.llm-timeout"),
                positiveDurationOrDefault(subagentTimeout, DefaultAgentSpecs.DEFAULT_SUBAGENT_TIMEOUT,
                        "we-flow.agent.subagent-timeout")
        );
    }

    public record MaxLoops(
            Integer lead,
            Integer subagent
    ) {
    }

    public record LeadToolCallLimit(
            Integer warningThreshold,
            Integer stopThreshold
    ) {
    }

    private static int positiveOrDefault(Integer value, int defaultValue, String propertyName) {
        if (value == null) {
            return defaultValue;
        }
        if (value < 1) {
            throw new IllegalArgumentException(propertyName + " must be positive");
        }
        return value;
    }

    private static Duration positiveDurationOrDefault(Duration value, Duration defaultValue, String propertyName) {
        Duration result = value == null ? defaultValue : value;
        if (result.isZero() || result.isNegative()) {
            throw new IllegalArgumentException(propertyName + " must be positive");
        }
        return result;
    }
}
