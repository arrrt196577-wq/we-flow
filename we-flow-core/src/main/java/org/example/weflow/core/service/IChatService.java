package org.example.weflow.core.service;

import java.util.function.Consumer;
import org.example.weflow.core.service.dto.ChatStreamRequest;

public interface IChatService {

    void stream(ChatStreamRequest request, Consumer<String> onChunk, Consumer<Throwable> onError, Runnable onComplete);
}
