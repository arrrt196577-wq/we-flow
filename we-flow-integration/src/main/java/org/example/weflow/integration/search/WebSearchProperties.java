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
        ProxyProperties proxy,
        JinaProperties jina
) {

    public static final String DEFAULT_PROVIDER = "jina";
    public static final int DEFAULT_MAX_RESULTS = 5;
    public static final int MAX_RESULTS_LIMIT = 10;
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(25);
    public static final String DEFAULT_REGION = "wt-wt";
    public static final String DEFAULT_SAFE_SEARCH = "moderate";
    public static final String DEFAULT_PROXY_HOST = "127.0.0.1";
    public static final int DEFAULT_PROXY_PORT = 7890;
    public static final String DEFAULT_JINA_BASE_URL = "https://s.jina.ai";
    public static final int DEFAULT_JINA_MAX_SNIPPET_CHARS = 800;
    public static final int DEFAULT_JINA_MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    public static final int DEFAULT_JINA_MAX_TOKENS = 8000;
    public static final boolean DEFAULT_JINA_AUTO_CHINESE_GL = true;
    public static final String DEFAULT_JINA_CHINESE_GL = "CN";

    public WebSearchProperties {
        enabled = enabled != null && enabled;
        provider = StringUtils.hasText(provider) ? provider.trim().toLowerCase() : DEFAULT_PROVIDER;
        maxResults = clamp(maxResults == null ? DEFAULT_MAX_RESULTS : maxResults, 1, MAX_RESULTS_LIMIT);
        timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
        region = StringUtils.hasText(region) ? region.trim() : DEFAULT_REGION;
        safeSearch = StringUtils.hasText(safeSearch) ? safeSearch.trim().toLowerCase() : DEFAULT_SAFE_SEARCH;
        proxy = proxy == null ? new ProxyProperties(false, null, null, null, null) : proxy;
        jina = jina == null ? new JinaProperties(null, null, null, null, null, null, null, null) : jina;
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

    public record JinaProperties(
            String apiKey,
            String baseUrl,
            Boolean noCache,
            Integer maxSnippetChars,
            Integer maxResponseBytes,
            Integer maxTokens,
            Boolean autoChineseGl,
            String chineseGl
    ) {

        public JinaProperties {
            apiKey = StringUtils.hasText(apiKey) ? apiKey.trim() : "";
            baseUrl = normalizeBaseUrl(StringUtils.hasText(baseUrl) ? baseUrl.trim() : DEFAULT_JINA_BASE_URL);
            noCache = noCache != null && noCache;
            maxSnippetChars = Math.max(0,
                    maxSnippetChars == null ? DEFAULT_JINA_MAX_SNIPPET_CHARS : maxSnippetChars);
            maxResponseBytes = Math.max(256 * 1024,
                    maxResponseBytes == null ? DEFAULT_JINA_MAX_RESPONSE_BYTES : maxResponseBytes);
            maxTokens = maxTokens == null ? DEFAULT_JINA_MAX_TOKENS : normalizeMaxTokens(maxTokens);
            autoChineseGl = autoChineseGl == null ? DEFAULT_JINA_AUTO_CHINESE_GL : autoChineseGl;
            chineseGl = chineseGl == null ? DEFAULT_JINA_CHINESE_GL : chineseGl.trim().toUpperCase();
        }

        public boolean isNoCache() {
            return Boolean.TRUE.equals(noCache);
        }

        public boolean isAutoChineseGl() {
            return Boolean.TRUE.equals(autoChineseGl);
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String normalizeBaseUrl(String value) {
        String normalized = value;
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static int normalizeMaxTokens(int value) {
        if (value <= 0) {
            return 0;
        }
        return Math.max(500, value);
    }
}
