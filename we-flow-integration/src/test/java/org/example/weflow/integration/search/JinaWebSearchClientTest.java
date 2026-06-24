package org.example.weflow.integration.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.transport.ProxyProvider;


class JinaWebSearchClientTest {

    private HttpServer server;
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicReference<String> responseBody = new AtomicReference<>("[]");
    private final AtomicReference<String> rawQuery = new AtomicReference<>("");
    private final AtomicReference<String> authorization = new AtomicReference<>("");
    private final AtomicReference<String> accept = new AtomicReference<>("");
    private final AtomicReference<String> noCache = new AtomicReference<>("");
    private final AtomicReference<String> maxTokens = new AtomicReference<>("");
    private final AtomicReference<String> timeout = new AtomicReference<>("");

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", this::handleSearch);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void searchShouldMapArrayResponseAndSendJinaRequest() {
        responseBody.set("""
                [
                  {"title":"First","url":"https://example.com/first","description":"First result"},
                  {"title":"Second","link":"https://example.com/second","content":"Second content"}
                ]
                """);

        WebSearchResponse response = client(5, 80, true)
                .search(new WebSearchRequest("jina ai", 2));

        assertThat(response.query()).isEqualTo("jina ai");
        assertThat(response.results()).containsExactly(
                new WebSearchResult("First", "https://example.com/first", "First result"),
                new WebSearchResult("Second", "https://example.com/second", "Second content"));
        assertThat(queryParam(rawQuery.get(), "q")).isEqualTo("jina ai");
        assertThat(queryParam(rawQuery.get(), "num")).isEqualTo("2");
        assertThat(authorization.get()).isEqualTo("Bearer test-key");
        assertThat(accept.get()).contains(MediaType.APPLICATION_JSON_VALUE);
        assertThat(noCache.get()).isEqualTo("true");
        assertThat(maxTokens.get()).isEqualTo("8000");
        assertThat(timeout.get()).isEqualTo("5");
    }

    @Test
    void searchShouldMapDataWrappedResponse() {
        responseBody.set("""
                {
                  "data": [
                    {"title":"Wrapped","url":"https://example.com/wrapped","description":"Wrapped result"}
                  ]
                }
                """);

        WebSearchResponse response = client(5, 80, false)
                .search(new WebSearchRequest("wrapped", 5));

        assertThat(response.results())
                .containsExactly(new WebSearchResult(
                        "Wrapped",
                        "https://example.com/wrapped",
                        "Wrapped result"));
    }

    @Test
    void parseResultsShouldUseContentFallbackAndTruncateSnippet() {
        List<WebSearchResult> results = JinaWebSearchClient.parseResults("""
                [
                  {
                    "title": "Content Result",
                    "url": "https://example.com/content",
                    "content": "0123456789abcdef"
                  }
                ]
                """, 5, 10);

        assertThat(results)
                .containsExactly(new WebSearchResult(
                        "Content Result",
                        "https://example.com/content",
                        "0123456789"));
    }

    @Test
    void parseResultsShouldRespectMaxResults() {
        List<WebSearchResult> results = JinaWebSearchClient.parseResults("""
                [
                  {"title":"One","url":"https://example.com/one","description":"One"},
                  {"title":"Two","url":"https://example.com/two","description":"Two"}
                ]
                """, 1, 80);

        assertThat(results)
                .containsExactly(new WebSearchResult("One", "https://example.com/one", "One"));
    }

    @Test
    void searchShouldReadLargeJinaResponses() {
        String largeContent = "x".repeat(300_000);
        responseBody.set("""
                {
                  "data": [
                    {
                      "title": "Large",
                      "url": "https://example.com/large",
                      "content": "%s"
                    }
                  ]
                }
                """.formatted(largeContent));

        WebSearchResponse response = client(5, 20, false)
                .search(new WebSearchRequest("large", 5));

        assertThat(response.results())
                .containsExactly(new WebSearchResult(
                        "Large",
                        "https://example.com/large",
                        "xxxxxxxxxxxxxxxxxxxx"));
    }

    @Test
    void searchShouldWrapRequestFailures() {
        status.set(500);
        responseBody.set("{\"error\":\"bad gateway\"}");

        assertThatThrownBy(() -> client(5, 80, false)
                .search(new WebSearchRequest("failure", 5)))
                .isInstanceOf(WebSearchException.class)
                .hasMessageContaining("Jina search failed");
    }


    private JinaWebSearchClient client(int maxResults, int maxSnippetChars, boolean noCache) {
        return new JinaWebSearchClient(WebClient.builder(), new WebSearchProperties(
                true,
                "jina",
                maxResults,
                Duration.ofSeconds(5),
                "wt-wt",
                "moderate",
                new WebSearchProperties.ProxyProperties(false, null, null, null, null),
                new WebSearchProperties.JinaProperties(
                        "test-key",
                        "http://localhost:" + server.getAddress().getPort(),
                        noCache,
                        maxSnippetChars,
                        null,
                        null)));
    }

    private void handleSearch(HttpExchange exchange) throws IOException {
        rawQuery.set(exchange.getRequestURI().getRawQuery());
        authorization.set(header(exchange, HttpHeaders.AUTHORIZATION));
        accept.set(header(exchange, HttpHeaders.ACCEPT));
        noCache.set(header(exchange, "x-no-cache"));
        maxTokens.set(header(exchange, "x-max-tokens"));
        timeout.set(header(exchange, "x-timeout"));

        byte[] body = responseBody.get().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        exchange.sendResponseHeaders(status.get(), body.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(body);
        }
    }

    private static String header(HttpExchange exchange, String name) {
        List<String> values = exchange.getRequestHeaders().get(name);
        return values == null || values.isEmpty() ? "" : values.get(0);
    }

    private static String queryParam(String rawQuery, String name) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return "";
        }
        for (String pair : rawQuery.split("&")) {
            int separator = pair.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            String key = URLDecoder.decode(pair.substring(0, separator), StandardCharsets.UTF_8);
            if (name.equals(key)) {
                return URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8);
            }
        }
        return "";
    }
}
