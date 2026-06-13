package org.example.weflow.integration.search;

import org.springframework.util.StringUtils;

public record WebSearchRequest(
        String query,
        int maxResults
) {

    public WebSearchRequest {
        if (!StringUtils.hasText(query)) {
            throw new IllegalArgumentException("query must not be blank");
        }
        query = query.trim();
        if (maxResults < 1) {
            throw new IllegalArgumentException("maxResults must be positive");
        }
    }
}
