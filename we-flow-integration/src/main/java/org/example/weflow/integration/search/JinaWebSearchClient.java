package org.example.weflow.integration.search;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

final class JinaWebSearchClient implements WebSearchClient {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String NO_CACHE_HEADER = "x-no-cache";
    private static final String MAX_TOKENS_HEADER = "x-max-tokens";
    private static final String TIMEOUT_HEADER = "x-timeout";

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
        try {
            String body = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/")
                            .queryParam("q", request.query())
                            .queryParam("num", maxResults)
                            .build())
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
                    .block(properties.timeout());

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

    private long jinaTimeoutSeconds() {
        long seconds = properties.timeout().toSeconds();
        return Math.max(1, Math.min(180, seconds));
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return cleanText(current.getMessage());
    }
}
