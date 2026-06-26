package org.example.weflow.integration.fetch;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

final class JinaWebFetchClient implements WebFetchClient {

    private static final Logger log = LoggerFactory.getLogger(JinaWebFetchClient.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String NO_CACHE_HEADER = "x-no-cache";
    private static final String MAX_TOKENS_HEADER = "x-max-tokens";
    private static final String TIMEOUT_HEADER = "x-timeout";
    private static final long X_TIMEOUT_BUFFER_SECONDS = 10;

    private final WebClient webClient;
    private final WebFetchProperties properties;

    JinaWebFetchClient(WebClient.Builder webClientBuilder, WebFetchProperties properties) {
        this.webClient = webClientBuilder
                .codecs(configurer -> configurer.defaultCodecs()
                        .maxInMemorySize(properties.jina().maxResponseBytes()))
                .baseUrl(properties.jina().baseUrl())
                .defaultHeader("User-Agent", "we-flow/1.0")
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.jina().apiKey())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
        this.properties = properties;
    }

    @Override
    public WebFetchResponse fetch(WebFetchRequest request) {
        int maxContentChars = Math.min(request.maxContentChars(), properties.maxContentChars());
        try {
            Mono<String> response = webClient.post()
                    .uri("/")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .headers(headers -> {
                        if (properties.jina().isNoCache()) {
                            headers.set(NO_CACHE_HEADER, "true");
                        }
                        if (properties.jina().maxTokens() > 0) {
                            headers.set(MAX_TOKENS_HEADER, String.valueOf(properties.jina().maxTokens()));
                        }
                        headers.set(TIMEOUT_HEADER, String.valueOf(jinaTimeoutSeconds()));
                    })
                    .body(BodyInserters.fromFormData("url", request.url()))
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(properties.timeout());

            String body = blockWithRetry(response, request.url());
            return parseResponse(body == null ? "" : body, request.url(), maxContentChars);
        } catch (WebFetchException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new WebFetchException("Jina fetch failed: " + rootMessage(e), e);
        }
    }

    static WebFetchResponse parseResponse(String json, String requestedUrl, int maxContentChars) {
        if (!StringUtils.hasText(json)) {
            throw new WebFetchException("Jina fetch response was empty");
        }

        JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new WebFetchException("Failed to parse Jina fetch response", e);
        }

        JsonNode data = root.path("data");
        if (!data.isObject()) {
            throw new WebFetchException("Failed to parse Jina fetch response: missing data");
        }

        String title = cleanMetadata(text(data, "title"));
        String resolvedUrl = cleanMetadata(text(data, "url"));
        if (!StringUtils.hasText(resolvedUrl)) {
            resolvedUrl = requestedUrl;
        }
        String content = cleanContent(text(data, "content"));
        int contentLength = content.length();
        boolean truncated = contentLength > maxContentChars;
        String outputContent = truncated ? content.substring(0, maxContentChars).trim() : content;

        return new WebFetchResponse(
                requestedUrl,
                resolvedUrl,
                title,
                outputContent,
                truncated,
                contentLength);
    }

    private static String text(JsonNode node, String fieldName) {
        JsonNode value = node.path(fieldName);
        return value.isTextual() ? value.asText() : "";
    }

    private static String cleanMetadata(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static String cleanContent(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\r\n", "\n")
                .replace('\r', '\n')
                .trim();
    }

    private long jinaTimeoutSeconds() {
        long seconds = properties.timeout().toSeconds() - X_TIMEOUT_BUFFER_SECONDS;
        return Math.max(1, Math.min(180, seconds));
    }

    private String blockWithRetry(Mono<String> response, String url) {
        WebFetchProperties.RetryProperties retry = properties.retry();
        if (!retry.isEnabled()) {
            return response.block();
        }
        return response.retryWhen(Retry.backoff(retry.maxRetries(), retry.initialBackoff())
                        .maxBackoff(retry.maxBackoff())
                        .jitter(retry.jitter())
                        .filter(this::isRetryable)
                        .doBeforeRetry(retrySignal -> logRetry(retrySignal.failure(),
                                retrySignal.totalRetries() + 2,
                                retry.maxAttempts(),
                                url))
                        .onRetryExhaustedThrow((retryBackoffSpec, retrySignal) -> retrySignal.failure()))
                .block();
    }

    private void logRetry(Throwable failure, long attempt, int maxAttempts, String url) {
        log.warn("Jina provider retry operation=jina_fetch attempt={} maxAttempts={} reason={} url={}",
                attempt, maxAttempts, retryReason(failure), cleanMetadata(url));
    }

    private String retryReason(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof WebClientResponseException responseException) {
                return "httpStatus=" + responseException.getStatusCode().value();
            }
            current = current.getCause();
        }
        Throwable root = rootCause(throwable);
        return root.getClass().getSimpleName() + ": " + cleanMetadata(root.getMessage());
    }

    private boolean isRetryable(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof TimeoutException || current instanceof WebClientRequestException) {
                return true;
            }
            if (current instanceof WebClientResponseException responseException) {
                int status = responseException.getStatusCode().value();
                return status == 429 || status >= 500;
            }
            current = current.getCause();
        }
        return false;
    }

    private static String rootMessage(Throwable throwable) {
        return cleanMetadata(rootCause(throwable).getMessage());
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
