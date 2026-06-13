package org.example.weflow.integration.search;

public class WebSearchException extends RuntimeException {

    public WebSearchException(String message, Throwable cause) {
        super(message, cause);
    }

    public WebSearchException(String message) {
        super(message);
    }
}
