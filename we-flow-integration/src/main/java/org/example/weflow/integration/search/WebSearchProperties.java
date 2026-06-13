package org.example.weflow.integration.search;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

@ConfigurationProperties(prefix = "we-flow.search")
public record WebSearchProperties(
        Boolean enabled,
        String provider,
        Integer maxResults,
        Duration timeout,
        String region,
        String safeSearch,
        ProxyProperties proxy
) {

    public static final String DEFAULT_PROVIDER = "duckduckgo";
    public static final int DEFAULT_MAX_RESULTS = 5;
    public static final int MAX_RESULTS_LIMIT = 10;
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);
    public static final String DEFAULT_REGION = "wt-wt";
    public static final String DEFAULT_SAFE_SEARCH = "moderate";
    public static final String DEFAULT_PROXY_HOST = "127.0.0.1";
    public static final int DEFAULT_PROXY_PORT = 7890;

    public WebSearchProperties {
        enabled = enabled != null && enabled;
        provider = StringUtils.hasText(provider) ? provider.trim().toLowerCase() : DEFAULT_PROVIDER;
        maxResults = clamp(maxResults == null ? DEFAULT_MAX_RESULTS : maxResults, 1, MAX_RESULTS_LIMIT);
        timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
        region = StringUtils.hasText(region) ? region.trim() : DEFAULT_REGION;
        safeSearch = StringUtils.hasText(safeSearch) ? safeSearch.trim().toLowerCase() : DEFAULT_SAFE_SEARCH;
        proxy = proxy == null ? new ProxyProperties(false, null, null, null, null) : proxy;
    }

    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }

    public record ProxyProperties(
            Boolean enabled,
            String host,
            Integer port,
            String username,
            String password
    ) {

        public ProxyProperties {
            enabled = enabled != null && enabled;
            host = StringUtils.hasText(host) ? host.trim() : DEFAULT_PROXY_HOST;
            port = port == null ? DEFAULT_PROXY_PORT : port;
            username = StringUtils.hasText(username) ? username.trim() : "";
            password = password == null ? "" : password;
        }

        public boolean isEnabled() {
            return Boolean.TRUE.equals(enabled);
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
