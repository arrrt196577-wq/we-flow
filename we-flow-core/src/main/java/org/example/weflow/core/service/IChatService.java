package org.example.weflow.core.service;

import java.util.function.Consumer;

public interface IChatService {

    void stream(String message, Consumer<String> onChunk, Consumer<Throwable> onError, Runnable onComplete);
}
