package org.example.weflow.integration.fetch;

public record WebFetchResponse(
        String requestedUrl,
        String resolvedUrl,
        String title,
        String content,
        boolean truncated,
        int contentLength
) {

    public WebFetchResponse {
        requestedUrl = requestedUrl == null ? "" : requestedUrl;
        resolvedUrl = resolvedUrl == null ? "" : resolvedUrl;
        title = title == null ? "" : title;
        content = content == null ? "" : content;
        contentLength = Math.max(contentLength, content.length());
    }
}
