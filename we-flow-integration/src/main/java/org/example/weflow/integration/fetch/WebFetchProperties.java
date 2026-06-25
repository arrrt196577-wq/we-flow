package org.example.weflow.integration.fetch;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

@ConfigurationProperties(prefix = "we-flow.fetch")
public record WebFetchProperties(
        Boolean enabled,
        String provider,
        Integer maxContentChars,
        Duration timeout,
        ProxyProperties proxy,
        JinaProperties jina
) {

    public static final String DEFAULT_PROVIDER = "jina";
    public static final int DEFAULT_MAX_CONTENT_CHARS = 12_000;
    public static final int MAX_CONTENT_CHARS_LIMIT = 100_000;
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);
    public static final String DEFAULT_PROXY_HOST = "127.0.0.1";
    public static final int DEFAULT_PROXY_PORT = 7890;
    public static final String DEFAULT_JINA_BASE_URL = "https://r.jina.ai";
    public static final int DEFAULT_JINA_MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    public static final int DEFAULT_JINA_MAX_TOKENS = 8000;

    public WebFetchProperties {
        enabled = enabled != null && enabled;
        provider = StringUtils.hasText(provider) ? provider.trim().toLowerCase() : DEFAULT_PROVIDER;
        maxContentChars = clamp(
                maxContentChars == null ? DEFAULT_MAX_CONTENT_CHARS : maxContentChars,
                1,
                MAX_CONTENT_CHARS_LIMIT);
        timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
        proxy = proxy == null ? new ProxyProperties(false, null, null, null, null) : proxy;
        jina = jina == null ? new JinaProperties(null, null, null, null, null) : jina;
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
            Integer maxResponseBytes,
            Integer maxTokens
    ) {

        public JinaProperties {
            apiKey = StringUtils.hasText(apiKey) ? apiKey.trim() : "";
            baseUrl = normalizeBaseUrl(StringUtils.hasText(baseUrl) ? baseUrl.trim() : DEFAULT_JINA_BASE_URL);
            noCache = noCache != null && noCache;
            maxResponseBytes = Math.max(256 * 1024,
                    maxResponseBytes == null ? DEFAULT_JINA_MAX_RESPONSE_BYTES : maxResponseBytes);
            maxTokens = maxTokens == null ? DEFAULT_JINA_MAX_TOKENS : normalizeMaxTokens(maxTokens);
        }

        public boolean isNoCache() {
            return Boolean.TRUE.equals(noCache);
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
