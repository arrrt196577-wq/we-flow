package org.example.weflow.integration.fetch;

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
import org.springframework.web.reactive.function.client.WebClient;

class JinaWebFetchClientTest {

    private HttpServer server;
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicReference<String> responseBody = new AtomicReference<>("{}");
    private final AtomicReference<String> method = new AtomicReference<>("");
    private final AtomicReference<String> requestBody = new AtomicReference<>("");
    private final AtomicReference<String> authorization = new AtomicReference<>("");
    private final AtomicReference<String> accept = new AtomicReference<>("");
    private final AtomicReference<String> contentType = new AtomicReference<>("");
    private final AtomicReference<String> noCache = new AtomicReference<>("");
    private final AtomicReference<String> maxTokens = new AtomicReference<>("");
    private final AtomicReference<String> timeout = new AtomicReference<>("");

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", this::handleFetch);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void fetchShouldMapDataResponseAndSendJinaRequest() {
        responseBody.set("""
                {
                  "code": 200,
                  "status": 20000,
                  "data": {
                    "title": "Example",
                    "url": "https://example.com/final",
                    "content": "Alpha\\nBeta"
                  }
                }
                """);

        WebFetchResponse response = client(80, true)
                .fetch(new WebFetchRequest("https://example.com/start", 50));

        assertThat(response.requestedUrl()).isEqualTo("https://example.com/start");
        assertThat(response.resolvedUrl()).isEqualTo("https://example.com/final");
        assertThat(response.title()).isEqualTo("Example");
        assertThat(response.content()).isEqualTo("Alpha\nBeta");
        assertThat(response.truncated()).isFalse();
        assertThat(response.contentLength()).isEqualTo("Alpha\nBeta".length());
        assertThat(method.get()).isEqualTo("POST");
        assertThat(formParam(requestBody.get(), "url")).isEqualTo("https://example.com/start");
        assertThat(authorization.get()).isEqualTo("Bearer test-key");
        assertThat(accept.get()).contains(MediaType.APPLICATION_JSON_VALUE);
        assertThat(contentType.get()).contains(MediaType.APPLICATION_FORM_URLENCODED_VALUE);
        assertThat(noCache.get()).isEqualTo("true");
        assertThat(maxTokens.get()).isEqualTo("8000");
        assertThat(timeout.get()).isEqualTo("5");
    }

    @Test
    void fetchShouldTruncateContentToRequestLimit() {
        responseBody.set("""
                {
                  "data": {
                    "title": "Long",
                    "url": "https://example.com/long",
                    "content": "0123456789abcdef"
                  }
                }
                """);

        WebFetchResponse response = client(80, false)
                .fetch(new WebFetchRequest("https://example.com/long", 10));

        assertThat(response.content()).isEqualTo("0123456789");
        assertThat(response.truncated()).isTrue();
        assertThat(response.contentLength()).isEqualTo(16);
    }

    @Test
    void fetchShouldClampContentToConfiguredLimit() {
        responseBody.set("""
                {
                  "data": {
                    "title": "Long",
                    "url": "https://example.com/long",
                    "content": "0123456789abcdef"
                  }
                }
                """);

        WebFetchResponse response = client(8, false)
                .fetch(new WebFetchRequest("https://example.com/long", 20));

        assertThat(response.content()).isEqualTo("01234567");
        assertThat(response.truncated()).isTrue();
        assertThat(response.contentLength()).isEqualTo(16);
    }

    @Test
    void fetchShouldWrapRequestFailures() {
        status.set(500);
        responseBody.set("{\"error\":\"bad gateway\"}");

        assertThatThrownBy(() -> client(80, false)
                .fetch(new WebFetchRequest("https://example.com/failure", 10)))
                .isInstanceOf(WebFetchException.class)
                .hasMessageContaining("Jina fetch failed");
    }

    @Test
    void parseResponseShouldFailWhenDataIsMissing() {
        assertThatThrownBy(() -> JinaWebFetchClient.parseResponse("{\"code\":400}", "https://example.com", 10))
                .isInstanceOf(WebFetchException.class)
                .hasMessageContaining("missing data");
    }

    private JinaWebFetchClient client(int maxContentChars, boolean noCache) {
        return new JinaWebFetchClient(WebClient.builder(), new WebFetchProperties(
                true,
                "jina",
                maxContentChars,
                Duration.ofSeconds(5),
                new WebFetchProperties.ProxyProperties(false, null, null, null, null),
                new WebFetchProperties.JinaProperties(
                        "test-key",
                        "http://localhost:" + server.getAddress().getPort(),
                        noCache,
                        null,
                        null)));
    }

    private void handleFetch(HttpExchange exchange) throws IOException {
        method.set(exchange.getRequestMethod());
        requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        authorization.set(header(exchange, HttpHeaders.AUTHORIZATION));
        accept.set(header(exchange, HttpHeaders.ACCEPT));
        contentType.set(header(exchange, HttpHeaders.CONTENT_TYPE));
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
        return values == null || values.isEmpty() ? "" : values.getFirst();
    }

    private static String formParam(String body, String name) {
        if (body == null || body.isBlank()) {
            return "";
        }
        for (String pair : body.split("&")) {
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
