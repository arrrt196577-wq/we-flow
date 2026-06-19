package org.example.weflow.workflow.agent;

import dev.langchain4j.data.message.ChatMessage;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.bsc.langgraph4j.prebuilt.MessagesState;

public class AgentThreadState extends MessagesState<ChatMessage> {

    public static final String CURRENT_USER_MESSAGE = "currentUserMessage";
    public static final String CURRENT_ASSISTANT_MESSAGE = "currentAssistantMessage";
    public static final String CURRENT_ASSISTANT_THINKING = "currentAssistantThinking";
    public static final String LOOP_COUNT = "loopCount";
    public static final String DEADLINE_EPOCH_MILLIS = "deadlineEpochMillis";
    public static final String FAILURE_CODE = "failureCode";
    public static final String FAILURE_MESSAGE = "failureMessage";
    public static final String LEAD_TOOL_CALL_COUNTS = "leadToolCallCounts";

    public AgentThreadState(Map<String, Object> initData) {
        super(initData);
    }

    public String currentUserMessage() {
        return this.<String>value(CURRENT_USER_MESSAGE).orElse("");
    }

    public String currentAssistantMessage() {
        return this.<String>value(CURRENT_ASSISTANT_MESSAGE).orElse("");
    }

    public String currentAssistantThinking() {
        return this.<String>value(CURRENT_ASSISTANT_THINKING).orElse("");
    }

    public int loopCount() {
        return intValue(LOOP_COUNT);
    }

    public Optional<Long> deadlineEpochMillis() {
        return this.<Object>value(DEADLINE_EPOCH_MILLIS).map(this::longValue);
    }

    public Optional<String> failureCode() {
        return this.<String>value(FAILURE_CODE).filter(value -> !value.isBlank());
    }

    public String failureMessage() {
        return this.<String>value(FAILURE_MESSAGE).orElse("");
    }

    public boolean hasFailure() {
        return failureCode().isPresent();
    }

    public Map<String, Integer> leadToolCallCounts() {
        Object value = this.value(LEAD_TOOL_CALL_COUNTS).orElse(Map.of());
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        map.forEach((key, count) -> {
            if (key != null) {
                counts.put(key.toString(), intValue(count));
            }
        });
        return counts;
    }

    private int intValue(String key) {
        return this.<Object>value(key)
                .map(this::intValue)
                .orElse(0);
    }

    private int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            return Integer.parseInt(text);
        }
        return 0;
    }

    private long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            return Long.parseLong(text);
        }
        return 0L;
    }
}
