package org.example.weflow.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;

import dev.langchain4j.agent.tool.Tool;
import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.example.weflow.integration.fetch.WebFetchClient;
import org.example.weflow.integration.fetch.WebFetchProperties;
import org.example.weflow.integration.fetch.WebFetchRequest;
import org.example.weflow.integration.fetch.WebFetchResponse;
import org.junit.jupiter.api.Test;

class WebFetchToolsTest {

    @Test
    void shouldExposeWebFetchLangChainTool() {
        Set<String> toolNames = Arrays.stream(WebFetchTools.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(Tool.class))
                .filter(annotation -> annotation != null)
                .map(Tool::name)
                .collect(Collectors.toSet());

        assertThat(toolNames).containsExactly("web_fetch");
    }

    @Test
    void webFetchShouldRejectBlankUrl() {
        WebFetchTools tools = tools(request -> response(request, "content"), 12_000);

        String result = tools.webFetch(" ", null);

        assertThat(result).contains("status: error");
        assertThat(result).contains("code: INVALID_ARGUMENT");
        assertThat(result).contains("url must not be blank");
    }

    @Test
    void webFetchShouldRejectNonHttpUrls() {
        AtomicReference<WebFetchRequest> capturedRequest = new AtomicReference<>();
        WebFetchTools tools = tools(request -> {
            capturedRequest.set(request);
            return response(request, "content");
        }, 12_000);

        String result = tools.webFetch("file:///tmp/readme.md", null);

        assertThat(result).contains("status: error");
        assertThat(result).contains("code: INVALID_ARGUMENT");
        assertThat(result).contains("url must use http or https");
        assertThat(capturedRequest.get()).isNull();
    }

    @Test
    void webFetchShouldClampMaxCharsToConfiguredLimit() {
        AtomicReference<WebFetchRequest> capturedRequest = new AtomicReference<>();
        WebFetchTools tools = tools(request -> {
            capturedRequest.set(request);
            return response(request, "content");
        }, 80);

        String result = tools.webFetch("https://example.com/docs", 99_999);

        assertThat(result).contains("status: success");
        assertThat(capturedRequest.get().maxContentChars()).isEqualTo(80);
    }

    @Test
    void webFetchShouldFormatSuccessfulResponse() {
        WebFetchTools tools = tools(request -> new WebFetchResponse(
                request.url(),
                "https://example.com/final",
                "Example",
                "Line one\nLine two",
                true,
                25), 12_000);

        String result = tools.webFetch("https://example.com/start", 10);

        assertThat(result).contains("status: success");
        assertThat(result).contains("url: https://example.com/final");
        assertThat(result).contains("title: Example");
        assertThat(result).contains("contentLength: 25");
        assertThat(result).contains("truncated: true");
        assertThat(result).contains("content:\nLine one\nLine two");
    }

    @Test
    void webFetchShouldFormatFetchFailure() {
        WebFetchTools tools = tools(request -> {
            throw new RuntimeException("network down");
        }, 12_000);

        String result = tools.webFetch("https://example.com/start", null);

        assertThat(result).contains("status: error");
        assertThat(result).contains("code: FETCH_FAILED");
        assertThat(result).contains("message: network down");
    }

    private WebFetchTools tools(WebFetchClient client, int maxContentChars) {
        return new WebFetchTools(client, new WebFetchProperties(
                true,
                "jina",
                maxContentChars,
                Duration.ofSeconds(10),
                null,
                null));
    }

    private WebFetchResponse response(WebFetchRequest request, String content) {
        return new WebFetchResponse(
                request.url(),
                request.url(),
                "Example",
                content,
                false,
                content.length());
    }
}
