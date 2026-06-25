package org.example.weflow.integration.search;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

final class JinaWebSearchClient implements WebSearchClient {

    private static final Logger log = LoggerFactory.getLogger(JinaWebSearchClient.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String NO_CACHE_HEADER = "x-no-cache";
    private static final String MAX_TOKENS_HEADER = "x-max-tokens";
    private static final String TIMEOUT_HEADER = "x-timeout";
    private static final long X_TIMEOUT_BUFFER_SECONDS = 10;

    private final WebClient webClient;
    private final WebSearchProperties properties;

    JinaWebSearchClient(WebClient.Builder webClientBuilder, WebSearchProperties properties) {
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
    public WebSearchResponse search(WebSearchRequest request) {
        int maxResults = Math.min(request.maxResults(), properties.maxResults());
        String gl = resolveGl(request.query());
        log.info("Jina search request query={} maxResults={} regionParam={}",
                cleanText(request.query()), maxResults, regionLogField(gl));
        try {
            Mono<String> response = webClient.get()
                    .uri(uriBuilder -> {
                        var builder = uriBuilder
                                .path("/")
                                .queryParam("q", request.query())
                                .queryParam("num", maxResults);
                        if (StringUtils.hasText(gl)) {
                            builder = builder.queryParam("gl", gl);
                        }
                        return builder.build();
                    })
                    .headers(headers -> {
                        if (properties.jina().isNoCache()) {
                            headers.set(NO_CACHE_HEADER, "true");
                        }
                        if (properties.jina().maxTokens() > 0) {
                            headers.set(MAX_TOKENS_HEADER, String.valueOf(properties.jina().maxTokens()));
                        }
                        headers.set(TIMEOUT_HEADER, String.valueOf(jinaTimeoutSeconds()));
                    })
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(properties.timeout());

            String body = blockWithRetry(response);
            List<WebSearchResult> results = parseResults(
                    body == null ? "" : body,
                    maxResults,
                    properties.jina().maxSnippetChars());
            return new WebSearchResponse(request.query(), results);
        } catch (WebSearchException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new WebSearchException("Jina search failed: " + rootMessage(e), e);
        }
    }

    static List<WebSearchResult> parseResults(String json, int maxResults, int maxSnippetChars) {
        if (!StringUtils.hasText(json) || maxResults < 1) {
            return List.of();
        }

        JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new WebSearchException("Failed to parse Jina search response", e);
        }

        JsonNode items = root.isArray() ? root : root.path("data");
        if (!items.isArray()) {
            return List.of();
        }

        List<WebSearchResult> results = new ArrayList<>();
        for (JsonNode item : items) {
            if (results.size() >= maxResults) {
                break;
            }

            String title = text(item, "title");
            String url = firstText(item, "url", "link");
            if (!StringUtils.hasText(title) || !StringUtils.hasText(url)) {
                continue;
            }

            String snippet = truncate(cleanText(firstText(item, "description", "content", "text")),
                    maxSnippetChars);
            results.add(new WebSearchResult(cleanText(title), cleanText(url), snippet));
        }
        return List.copyOf(results);
    }

    private static String firstText(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            String value = text(node, fieldName);
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return "";
    }

    private static String text(JsonNode node, String fieldName) {
        JsonNode value = node.path(fieldName);
        return value.isTextual() ? value.asText() : "";
    }

    private static String cleanText(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static String truncate(String value, int maxChars) {
        if (maxChars < 1 || value.length() <= maxChars) {
            return maxChars < 1 ? "" : value;
        }
        return value.substring(0, maxChars).trim();
    }

    private static String regionLogField(String gl) {
        return StringUtils.hasText(gl) ? "gl=" + gl : "default";
    }

    private long jinaTimeoutSeconds() {
        long seconds = properties.timeout().toSeconds() - X_TIMEOUT_BUFFER_SECONDS;
        return Math.max(1, Math.min(180, seconds));
    }

    private String blockWithRetry(Mono<String> response) {
        WebSearchProperties.RetryProperties retry = properties.retry();
        if (!retry.isEnabled()) {
            return response.block();
        }
        return response.retryWhen(Retry.backoff(retry.maxRetries(), retry.initialBackoff())
                        .maxBackoff(retry.maxBackoff())
                        .jitter(retry.jitter())
                        .filter(this::isRetryable)
                        .onRetryExhaustedThrow((retryBackoffSpec, retrySignal) -> retrySignal.failure()))
                .block();
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

    private String resolveGl(String query) {
        if (!properties.jina().isAutoChineseGl()
                || !StringUtils.hasText(properties.jina().chineseGl())
                || !containsHanScript(query)) {
            return "";
        }
        return properties.jina().chineseGl();
    }

    private static boolean containsHanScript(String value) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN) {
                return true;
            }
            offset += Character.charCount(codePoint);
        }
        return false;
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return cleanText(current.getMessage());
    }
}
