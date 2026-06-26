package org.example.weflow.workflow.agent.runtime;

import java.util.LinkedHashMap;
import java.util.Map;
import org.example.weflow.workflow.agent.AgentThreadState;

public final class LeadToolCallLimitMiddleware implements WeflowMiddleware {

    @Override
    public Map<String, Object> aroundTurnInitialization(
            TurnInitializationContext context,
            TurnInitializationCall next
    ) {
        Map<String, Object> update = new LinkedHashMap<>(next.call(context));
        update.put(AgentThreadState.LEAD_TOOL_CALL_COUNTS, Map.of());
        return update;
    }
}
