package org.example.weflow.workflow.chat;

import java.util.Map;
import org.bsc.langgraph4j.prebuilt.MessagesState;

public class ChatHarnessState extends MessagesState<ChatHarnessMessage> {

    static final String CURRENT_USER_MESSAGE = "currentUserMessage";
    static final String CURRENT_ASSISTANT_MESSAGE = "currentAssistantMessage";

    public ChatHarnessState(Map<String, Object> initData) {
        super(initData);
    }

    public String currentUserMessage() {
        return this.<String>value(CURRENT_USER_MESSAGE).orElse("");
    }

    public String currentAssistantMessage() {
        return this.<String>value(CURRENT_ASSISTANT_MESSAGE).orElse("");
    }
}
