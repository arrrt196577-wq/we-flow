package org.example.weflow.workflow.agent;

import dev.langchain4j.data.message.ChatMessage;
import java.util.Map;
import org.bsc.langgraph4j.prebuilt.MessagesState;

public class AgentThreadState extends MessagesState<ChatMessage> {

    public static final String CURRENT_USER_MESSAGE = "currentUserMessage";
    public static final String CURRENT_ASSISTANT_MESSAGE = "currentAssistantMessage";
    public static final String CURRENT_ASSISTANT_THINKING = "currentAssistantThinking";
    public static final String TOOL_ITERATION_COUNT = "toolIterationCount";

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

    public int toolIterationCount() {
        return this.<Integer>value(TOOL_ITERATION_COUNT).orElse(0);
    }
}
