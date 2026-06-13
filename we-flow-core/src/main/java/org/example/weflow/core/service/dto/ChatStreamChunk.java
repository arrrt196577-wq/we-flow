package org.example.weflow.core.service.dto;

public record ChatStreamChunk(
        Type type,
        String content
) {

    public static ChatStreamChunk content(String content) {
        return new ChatStreamChunk(Type.CONTENT, content);
    }

    public static ChatStreamChunk reasoning(String content) {
        return new ChatStreamChunk(Type.REASONING, content);
    }

    public enum Type {
        CONTENT("chunk"),
        REASONING("reasoning");

        private final String eventName;

        Type(String eventName) {
            this.eventName = eventName;
        }

        public String eventName() {
            return eventName;
        }
    }
}
