package org.example.weflow.integration.search;

import java.util.List;

public record WebSearchResponse(
        String query,
        List<WebSearchResult> results
) {

    public WebSearchResponse {
        results = results == null ? List.of() : List.copyOf(results);
    }

    public int totalResults() {
        return results.size();
    }
}
