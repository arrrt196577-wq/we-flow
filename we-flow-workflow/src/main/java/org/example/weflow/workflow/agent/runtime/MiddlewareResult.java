package org.example.weflow.workflow.agent.runtime;

import java.util.Map;
import java.util.Objects;

public record MiddlewareResult(
        Type type,
        Map<String, Object> update,
        String failureCode,
        String failureMessage,
        String retryFeedback
) {

    public MiddlewareResult {
        Objects.requireNonNull(type, "type must not be null");
        update = update == null ? Map.of() : Map.copyOf(update);
        failureCode = failureCode == null ? "" : failureCode;
        failureMessage = failureMessage == null ? "" : failureMessage;
        retryFeedback = retryFeedback == null ? "" : retryFeedback;
    }

    public static MiddlewareResult continueProcessing() {
        return new MiddlewareResult(Type.CONTINUE, Map.of(), "", "", "");
    }

    public static MiddlewareResult shortCircuit(Map<String, Object> update) {
        return new MiddlewareResult(Type.SHORT_CIRCUIT, update, "", "", "");
    }

    public static MiddlewareResult fail(String code, String message) {
        return new MiddlewareResult(Type.FAIL, Map.of(), code, message, "");
    }

    public static MiddlewareResult retry(String feedback) {
        return new MiddlewareResult(Type.RETRY, Map.of(), "", "", feedback);
    }

    public boolean isContinue() {
        return type == Type.CONTINUE;
    }

    public enum Type {
        CONTINUE,
        SHORT_CIRCUIT,
        RETRY,
        FAIL
    }
}
