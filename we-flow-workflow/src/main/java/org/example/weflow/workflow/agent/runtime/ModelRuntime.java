package org.example.weflow.workflow.agent.runtime;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import org.bsc.langgraph4j.RunnableConfig;
import org.example.weflow.core.agent.AgentSpec;
import org.example.weflow.core.service.dto.ChatStreamChunk;
import org.example.weflow.workflow.agent.AgentStreamSinkRegistry;
import org.example.weflow.workflow.agent.AgentThreadState;

public final class ModelRuntime {

    private final StreamingChatModel streamingChatModel;
    private final AgentStreamSinkRegistry sinkRegistry;
    private final MiddlewareManager middlewareManager;

    public ModelRuntime(
            StreamingChatModel streamingChatModel,
            AgentStreamSinkRegistry sinkRegistry,
            MiddlewareManager middlewareManager
    ) {
        this.streamingChatModel = streamingChatModel;
        this.sinkRegistry = sinkRegistry;
        this.middlewareManager = middlewareManager;
    }

    public Optional<Consumer<ChatStreamChunk>> partialSink(RunnableConfig config) {
        return config.threadId().flatMap(sinkRegistry::sink);
    }

    public AiMessage call(
            AgentRunContext runContext,
            AgentThreadState state,
            ChatRequest request,
            Duration timeout,
            Consumer<ChatStreamChunk> partialSink
    ) {
        ModelCallContext context = new ModelCallContext(runContext, state, request, timeout, partialSink);
        return middlewareManager.aroundModel(context, this::callChatModel);
    }

    public Duration effectiveLlmTimeout(AgentSpec spec, AgentThreadState state) {
        Duration llmTimeout = spec.runtimeLimits().llmTimeout();
        return remainingOverallTimeout(state)
                .filter(remaining -> remaining.compareTo(llmTimeout) < 0)
                .orElse(llmTimeout);
    }

    public boolean timeoutUsesOverallDeadline(AgentSpec spec, AgentThreadState state, Duration effectiveTimeout) {
        return remainingOverallTimeout(state)
                .map(remaining -> remaining.compareTo(spec.runtimeLimits().llmTimeout()) <= 0
                        && effectiveTimeout.compareTo(remaining) <= 0)
                .orElse(false);
    }

    public Optional<Duration> remainingOverallTimeout(AgentThreadState state) {
        return state.deadlineEpochMillis()
                .map(deadline -> Duration.ofMillis(deadline - Instant.now().toEpochMilli()));
    }

    public boolean overallTimeoutExceeded(AgentThreadState state) {
        return remainingOverallTimeout(state)
                .map(remaining -> !remaining.isPositive())
                .orElse(false);
    }

    public boolean isTimeout(Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current instanceof TimeoutException;
    }

    private AiMessage callChatModel(ModelCallContext context) {
        CompletableFuture<AiMessage> responseFuture = new CompletableFuture<>();
        StringBuilder responseBuilder = new StringBuilder();
        StringBuilder thinkingBuilder = new StringBuilder();
        Consumer<ChatStreamChunk> contentSink = context.partialSink();

        streamingChatModel.chat(context.request(), new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                responseBuilder.append(partialResponse);
                if (contentSink != null && partialResponse != null && !partialResponse.isEmpty()) {
                    contentSink.accept(ChatStreamChunk.content(partialResponse));
                }
            }

            @Override
            public void onPartialThinking(PartialThinking partialThinking) {
                thinkingBuilder.append(partialThinking.text());
            }

            @Override
            public void onCompleteResponse(ChatResponse response) {
                AiMessage aiMessage = response.aiMessage();
                if (aiMessage == null) {
                    responseFuture.complete(aiMessage(responseBuilder.toString(), thinkingBuilder.toString()));
                    return;
                }
                if (!aiMessage.hasToolExecutionRequests()
                        && (aiMessage.text() == null || aiMessage.text().isBlank())
                        && !responseBuilder.isEmpty()) {
                    responseFuture.complete(aiMessage(responseBuilder.toString(), thinkingBuilder.toString()));
                    return;
                }
                responseFuture.complete(withThinkingFallback(aiMessage, thinkingBuilder.toString()));
            }

            @Override
            public void onError(Throwable throwable) {
                responseFuture.completeExceptionally(throwable);
            }
        });

        return responseFuture.orTimeout(Math.max(context.timeout().toMillis(), 1L), TimeUnit.MILLISECONDS).join();
    }

    private AiMessage aiMessage(String text, String thinking) {
        if (thinking == null || thinking.isBlank()) {
            return AiMessage.from(text);
        }
        return AiMessage.builder()
                .text(text)
                .thinking(thinking)
                .build();
    }

    private AiMessage withThinkingFallback(AiMessage aiMessage, String thinking) {
        if (aiMessage.thinking() != null && !aiMessage.thinking().isBlank()) {
            return aiMessage;
        }
        if (thinking == null || thinking.isBlank()) {
            return aiMessage;
        }
        return aiMessage.toBuilder()
                .thinking(thinking)
                .build();
    }
}
