package org.example.weflow.agent.tool;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import java.util.List;
import org.example.weflow.integration.search.WebSearchClient;
import org.example.weflow.integration.search.WebSearchException;
import org.example.weflow.integration.search.WebSearchProperties;
import org.example.weflow.integration.search.WebSearchRequest;
import org.example.weflow.integration.search.WebSearchResponse;
import org.example.weflow.integration.search.WebSearchResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "we-flow.search", name = "enabled", havingValue = "true")
public class WebSearchTools implements AgentTool {

    private static final int MAX_LOG_FIELD_CHARS = 500;

    private final WebSearchClient webSearchClient;
    private final WebSearchProperties properties;

    public WebSearchTools(WebSearchClient webSearchClient, WebSearchProperties properties) {
        this.webSearchClient = webSearchClient;
        this.properties = properties;
    }

    @Tool(name = "web_search", value = "Search the web for current or external public information.")
    public String webSearch(
            @P("Search keywords describing what to find. Be specific for better results.") String query,
            @P(value = "Maximum number of search results to return.", required = false) Integer maxResults
    ) {
        log.info("Tool called: web_search query={}, maxResults={}", sanitize(query), maxResults);
        if (!StringUtils.hasText(query)) {
            log.warn("Tool result: web_search status=error code=INVALID_ARGUMENT message={}",
                    logField("query must not be blank"));
            return error("INVALID_ARGUMENT", "query must not be blank");
        }

        int limit = clamp(maxResults, properties.maxResults(), 1, properties.maxResults());
        try {
            WebSearchResponse response = webSearchClient.search(new WebSearchRequest(query, limit));
            logSearchResult(response);
            return format(response);
        } catch (WebSearchException e) {
            log.warn("Tool result: web_search status=error code=SEARCH_FAILED message={}", logField(e.getMessage()));
            return error("SEARCH_FAILED", e.getMessage());
        } catch (RuntimeException e) {
            log.warn("Tool result: web_search status=error code=SEARCH_FAILED message={}", logField(e.getMessage()));
            return error("SEARCH_FAILED", e.getMessage());
        }
    }

    private String format(WebSearchResponse response) {
        StringBuilder result = new StringBuilder()
                .append("status: success\n")
                .append("query: ").append(sanitize(response.query())).append('\n')
                .append("totalResults: ").append(response.totalResults()).append('\n')
                .append("results:\n");

        if (response.results().isEmpty()) {
            result.append("(none)\n");
            return result.toString();
        }

        int index = 1;
        for (WebSearchResult searchResult : response.results()) {
            result.append(index++).append(". title: ").append(sanitize(searchResult.title())).append('\n')
                    .append("   url: ").append(sanitize(searchResult.url())).append('\n')
                    .append("   snippet: ").append(sanitize(searchResult.snippet())).append('\n');
        }
        return result.toString();
    }

    private void logSearchResult(WebSearchResponse response) {
        log.info("Tool result: web_search status=success query={} totalResults={} results={}",
                logField(response.query()), response.totalResults(), logResults(response.results()));
    }

    private String logResults(List<WebSearchResult> results) {
        if (results.isEmpty()) {
            return "[]";
        }

        StringBuilder builder = new StringBuilder("[");
        int rank = 1;
        for (WebSearchResult result : results) {
            if (rank > 1) {
                builder.append(", ");
            }
            builder.append("{rank=").append(rank)
                    .append(", title=\"").append(logField(result.title())).append('"')
                    .append(", url=\"").append(logField(result.url())).append('"')
                    .append(", snippet=\"").append(logField(result.snippet())).append("\"}");
            rank++;
        }
        return builder.append(']').toString();
    }

    private int clamp(Integer value, int defaultValue, int min, int max) {
        if (value == null) {
            return defaultValue;
        }
        return Math.max(min, Math.min(max, value));
    }

    private String sanitize(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private String logField(String value) {
        String sanitized = sanitize(value);
        if (sanitized.length() > MAX_LOG_FIELD_CHARS) {
            sanitized = sanitized.substring(0, MAX_LOG_FIELD_CHARS).trim() + "...";
        }
        return sanitized.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String error(String code, String message) {
        return "status: error\n"
                + "code: " + code + "\n"
                + "message: " + sanitize(message) + "\n";
    }
}
