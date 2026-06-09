package org.example.weflow.workflow.chat;

import dev.langchain4j.data.message.ChatMessage;
import java.util.Map;
import org.bsc.langgraph4j.prebuilt.MessagesState;

public class ChatHarnessState extends MessagesState<ChatMessage> {

    static final String CURRENT_USER_MESSAGE = "currentUserMessage";
    static final String CURRENT_ASSISTANT_MESSAGE = "currentAssistantMessage";
    static final String TOOL_ITERATION_COUNT = "toolIterationCount";

    public ChatHarnessState(Map<String, Object> initData) {
        super(initData);
    }

    public String currentUserMessage() {
        return this.<String>value(CURRENT_USER_MESSAGE).orElse("");
    }

    public String currentAssistantMessage() {
        return this.<String>value(CURRENT_ASSISTANT_MESSAGE).orElse("");
    }

    public int toolIterationCount() {
        return this.<Integer>value(TOOL_ITERATION_COUNT).orElse(0);
    }
}
