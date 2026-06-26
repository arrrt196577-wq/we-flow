package org.example.weflow.workflow.agent.runtime;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.example.weflow.workflow.agent.AgentThreadState;

public final class RuntimeLimitMiddleware implements WeflowMiddleware {

    @Override
    public Map<String, Object> aroundTurnInitialization(
            TurnInitializationContext context,
            TurnInitializationCall next
    ) {
        Map<String, Object> update = new LinkedHashMap<>(next.call(context));
        if (!context.runContext().spec().runtimeLimits().hasOverallTimeout()) {
            return update;
        }
        update.put(
                AgentThreadState.DEADLINE_EPOCH_MILLIS,
                Instant.now().plus(context.runContext().spec().runtimeLimits().overallTimeout()).toEpochMilli()
        );
        return update;
    }
}
