package org.example.weflow.integration.fetch;

public class WebFetchException extends RuntimeException {

    public WebFetchException(String message, Throwable cause) {
        super(message, cause);
    }

    public WebFetchException(String message) {
        super(message);
    }
}
