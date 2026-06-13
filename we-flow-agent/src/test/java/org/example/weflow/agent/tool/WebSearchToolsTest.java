package org.example.weflow.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;

import dev.langchain4j.agent.tool.Tool;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.example.weflow.integration.search.WebSearchClient;
import org.example.weflow.integration.search.WebSearchProperties;
import org.example.weflow.integration.search.WebSearchRequest;
import org.example.weflow.integration.search.WebSearchResponse;
import org.example.weflow.integration.search.WebSearchResult;
import org.junit.jupiter.api.Test;

class WebSearchToolsTest {

    @Test
    void shouldExposeWebSearchLangChainTool() {
        Set<String> toolNames = Arrays.stream(WebSearchTools.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(Tool.class))
                .filter(annotation -> annotation != null)
                .map(Tool::name)
                .collect(Collectors.toSet());

        assertThat(toolNames).containsExactly("web_search");
    }

    @Test
    void webSearchShouldRejectBlankQuery() {
        WebSearchTools tools = tools(request -> new WebSearchResponse(request.query(), List.of()), 5);

        String result = tools.webSearch(" ", null);

        assertThat(result).contains("status: error");
        assertThat(result).contains("code: INVALID_ARGUMENT");
    }

    @Test
    void webSearchShouldClampMaxResultsToConfiguredLimit() {
        AtomicReference<WebSearchRequest> capturedRequest = new AtomicReference<>();
        WebSearchTools tools = tools(request -> {
            capturedRequest.set(request);
            return new WebSearchResponse(request.query(), List.of());
        }, 2);

        String result = tools.webSearch("spring boot", 99);

        assertThat(result).contains("status: success");
        assertThat(capturedRequest.get().maxResults()).isEqualTo(2);
    }

    @Test
    void webSearchShouldFormatSuccessfulResults() {
        WebSearchTools tools = tools(request -> new WebSearchResponse(request.query(), List.of(
                new WebSearchResult("Spring Boot", "https://spring.io/projects/spring-boot", "Spring Boot docs")
        )), 5);

        String result = tools.webSearch("spring boot", 5);

        assertThat(result).contains("status: success");
        assertThat(result).contains("query: spring boot");
        assertThat(result).contains("totalResults: 1");
        assertThat(result).contains("title: Spring Boot");
        assertThat(result).contains("url: https://spring.io/projects/spring-boot");
        assertThat(result).contains("snippet: Spring Boot docs");
    }

    private WebSearchTools tools(WebSearchClient client, int maxResults) {
        return new WebSearchTools(client, new WebSearchProperties(
                true,
                "duckduckgo",
                maxResults,
                Duration.ofSeconds(10),
                "wt-wt",
                "moderate",
                null));
    }
}
