package org.example.weflow.integration.search;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

final class DuckDuckGoWebSearchClient implements WebSearchClient {

    private static final String BASE_URL = "https://html.duckduckgo.com";

    private final WebClient webClient;
    private final WebSearchProperties properties;

    DuckDuckGoWebSearchClient(WebClient.Builder webClientBuilder, WebSearchProperties properties) {
        this.webClient = webClientBuilder
                .baseUrl(BASE_URL)
                .defaultHeader("User-Agent", "we-flow/1.0")
                .build();
        this.properties = properties;
    }

    @Override
    public WebSearchResponse search(WebSearchRequest request) {
        int maxResults = Math.min(request.maxResults(), properties.maxResults());
        try {
            String html = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/html/")
                            .queryParam("q", request.query())
                            .queryParam("kl", properties.region())
                            .queryParam("kp", safeSearchParameter(properties.safeSearch()))
                            .build())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(properties.timeout());

            List<WebSearchResult> results = parseResults(html == null ? "" : html, maxResults);
            return new WebSearchResponse(request.query(), results);
        } catch (RuntimeException e) {
            throw new WebSearchException("DuckDuckGo search failed", e);
        }
    }

    static List<WebSearchResult> parseResults(String html, int maxResults) {
        if (!StringUtils.hasText(html) || maxResults < 1) {
            return List.of();
        }

        Document document = Jsoup.parse(html);
        List<WebSearchResult> results = new ArrayList<>();
        for (Element resultBlock : document.select(".result")) {
            if (results.size() >= maxResults) {
                break;
            }
            if (resultBlock.hasClass("result--ad")) {
                continue;
            }

            Element titleLink = first(resultBlock, "a.result__a", ".result__title a", "h2 a");
            if (titleLink == null) {
                continue;
            }

            String title = titleLink.text().trim();
            String url = normalizeResultUrl(titleLink.attr("href"));
            if (!StringUtils.hasText(title) || !StringUtils.hasText(url)) {
                continue;
            }

            Element snippetElement = first(resultBlock, ".result__snippet", ".result__body", ".snippet");
            String snippet = snippetElement == null ? "" : snippetElement.text().trim();
            results.add(new WebSearchResult(title, url, snippet));
        }
        return List.copyOf(results);
    }

    private static Element first(Element element, String... selectors) {
        for (String selector : selectors) {
            Element selected = element.selectFirst(selector);
            if (selected != null) {
                return selected;
            }
        }
        return null;
    }

    private static String normalizeResultUrl(String href) {
        if (!StringUtils.hasText(href)) {
            return "";
        }

        String normalized = href.trim();
        if (normalized.startsWith("//")) {
            normalized = "https:" + normalized;
        } else if (normalized.startsWith("/")) {
            normalized = BASE_URL + normalized;
        }

        String uddg = queryParameter(normalized, "uddg");
        if (StringUtils.hasText(uddg)) {
            return uddg;
        }
        return normalized;
    }

    private static String queryParameter(String url, String name) {
        try {
            String query = URI.create(url).getRawQuery();
            if (!StringUtils.hasText(query)) {
                return "";
            }

            for (String pair : query.split("&")) {
                int separator = pair.indexOf('=');
                if (separator <= 0) {
                    continue;
                }
                String key = URLDecoder.decode(pair.substring(0, separator), StandardCharsets.UTF_8);
                if (name.equals(key)) {
                    return URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8);
                }
            }
            return "";
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    private static String safeSearchParameter(String safeSearch) {
        return switch (safeSearch) {
            case "strict" -> "1";
            case "off", "none" -> "-2";
            default -> "-1";
        };
    }
}
