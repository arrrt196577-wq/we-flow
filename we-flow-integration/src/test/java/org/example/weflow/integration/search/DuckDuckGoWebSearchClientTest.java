package org.example.weflow.integration.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class DuckDuckGoWebSearchClientTest {

    @Test
    void parseResultsShouldNormalizeDuckDuckGoRedirectUrls() {
        String html = """
                <html>
                  <body>
                    <div class="result results_links_deep web-result">
                      <h2 class="result__title">
                        <a class="result__a" href="/l/?uddg=https%3A%2F%2Fexample.com%2Farticle&amp;rut=abc">Example Article</a>
                      </h2>
                      <a class="result__snippet">An example search result snippet.</a>
                    </div>
                    <div class="result results_links_deep web-result">
                      <a class="result__a" href="https://docs.example.com/page">Docs Page</a>
                      <div class="result__snippet">A documentation result.</div>
                    </div>
                  </body>
                </html>
                """;

        List<WebSearchResult> results = DuckDuckGoWebSearchClient.parseResults(html, 10);

        assertThat(results).hasSize(2);
        assertThat(results.get(0))
                .isEqualTo(new WebSearchResult(
                        "Example Article",
                        "https://example.com/article",
                        "An example search result snippet."));
        assertThat(results.get(1))
                .isEqualTo(new WebSearchResult(
                        "Docs Page",
                        "https://docs.example.com/page",
                        "A documentation result."));
    }

    @Test
    void parseResultsShouldRespectMaxResults() {
        String html = """
                <div class="result"><a class="result__a" href="https://example.com/1">One</a></div>
                <div class="result"><a class="result__a" href="https://example.com/2">Two</a></div>
                """;

        List<WebSearchResult> results = DuckDuckGoWebSearchClient.parseResults(html, 1);

        assertThat(results)
                .containsExactly(new WebSearchResult("One", "https://example.com/1", ""));
    }

    @Test
    void parseResultsShouldReturnEmptyListWhenNoResultsExist() {
        List<WebSearchResult> results = DuckDuckGoWebSearchClient.parseResults("<html><body>No results</body></html>", 5);

        assertThat(results).isEmpty();
    }

    @Test
    void parseResultsShouldSkipMalformedResultBlocks() {
        String html = """
                <div class="result"><a class="result__a" href="">Missing Url</a></div>
                <div class="result"><a class="result__a" href="https://example.com/ok">Valid Result</a></div>
                """;

        List<WebSearchResult> results = DuckDuckGoWebSearchClient.parseResults(html, 5);

        assertThat(results)
                .containsExactly(new WebSearchResult("Valid Result", "https://example.com/ok", ""));
    }
}
