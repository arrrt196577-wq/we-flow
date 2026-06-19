package org.example.weflow.agent.tool;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.example.weflow.integration.fetch.WebFetchClient;
import org.example.weflow.integration.fetch.WebFetchException;
import org.example.weflow.integration.fetch.WebFetchProperties;
import org.example.weflow.integration.fetch.WebFetchRequest;
import org.example.weflow.integration.fetch.WebFetchResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "we-flow.fetch", name = "enabled", havingValue = "true")
public class WebFetchTools implements AgentTool {

    private static final int MAX_LOG_FIELD_CHARS = 500;

    private final WebFetchClient webFetchClient;
    private final WebFetchProperties properties;

    public WebFetchTools(WebFetchClient webFetchClient, WebFetchProperties properties) {
        this.webFetchClient = webFetchClient;
        this.properties = properties;
    }

    @Tool(name = "web_fetch", value = "Fetch readable content from a public HTTP or HTTPS web page URL.")
    public String webFetch(
            @P("Public HTTP or HTTPS URL to fetch.") String url,
            @P(value = "Maximum number of content characters to return.", required = false) Integer maxChars
    ) {
        log.info("Tool called: web_fetch url={}, maxChars={}", sanitize(url), maxChars);
        if (!StringUtils.hasText(url)) {
            log.warn("Tool result: web_fetch status=error code=INVALID_ARGUMENT message={}",
                    logField("url must not be blank"));
            return error("INVALID_ARGUMENT", "url must not be blank");
        }

        int limit = clamp(maxChars, properties.maxContentChars(), 1, properties.maxContentChars());
        try {
            WebFetchResponse response = webFetchClient.fetch(new WebFetchRequest(url, limit));
            logFetchResult(response);
            return format(response);
        } catch (IllegalArgumentException e) {
            log.warn("Tool result: web_fetch status=error code=INVALID_ARGUMENT message={}", logField(e.getMessage()));
            return error("INVALID_ARGUMENT", e.getMessage());
        } catch (WebFetchException e) {
            log.warn("Tool result: web_fetch status=error code=FETCH_FAILED message={}", logField(e.getMessage()));
            return error("FETCH_FAILED", e.getMessage());
        } catch (RuntimeException e) {
            log.warn("Tool result: web_fetch status=error code=FETCH_FAILED message={}", logField(e.getMessage()));
            return error("FETCH_FAILED", e.getMessage());
        }
    }

    private String format(WebFetchResponse response) {
        StringBuilder result = new StringBuilder()
                .append("status: success\n")
                .append("url: ").append(sanitize(response.resolvedUrl())).append('\n')
                .append("title: ").append(sanitize(response.title())).append('\n')
                .append("contentLength: ").append(response.contentLength()).append('\n')
                .append("truncated: ").append(response.truncated()).append('\n')
                .append("content:\n")
                .append(response.content());
        if (!response.content().endsWith("\n")) {
            result.append('\n');
        }
        return result.toString();
    }

    private void logFetchResult(WebFetchResponse response) {
        log.info("Tool result: web_fetch status=success url={} title={} contentLength={} truncated={}",
                logField(response.resolvedUrl()), logField(response.title()), response.contentLength(), response.truncated());
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
