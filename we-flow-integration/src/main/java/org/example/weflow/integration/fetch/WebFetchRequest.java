package org.example.weflow.integration.fetch;

import java.net.URI;
import org.springframework.util.StringUtils;

public record WebFetchRequest(
        String url,
        int maxContentChars
) {

    public WebFetchRequest {
        if (!StringUtils.hasText(url)) {
            throw new IllegalArgumentException("url must not be blank");
        }
        url = url.trim();
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("url must be a valid HTTP or HTTPS URL", e);
        }
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("url must use http or https");
        }
        if (!StringUtils.hasText(uri.getHost())) {
            throw new IllegalArgumentException("url must include a host");
        }
        if (maxContentChars < 1) {
            throw new IllegalArgumentException("maxContentChars must be positive");
        }
    }
}
