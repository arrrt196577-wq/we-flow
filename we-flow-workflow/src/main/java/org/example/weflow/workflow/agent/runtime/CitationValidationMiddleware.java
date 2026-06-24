package org.example.weflow.workflow.agent.runtime;

import dev.langchain4j.data.message.ToolExecutionResultMessage;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CitationValidationMiddleware extends SearchAgentValidationSupport
        implements WeflowMiddleware {

    private static final String WEB_SEARCH_TOOL = "web_search";
    private static final String WEB_FETCH_TOOL = "web_fetch";
    private static final Pattern VALID_CITATION =
            Pattern.compile("\\[citation:([^\\]\\r\\n]+)]\\((https?://[^\\s)]+)\\)");
    private static final Pattern EXTERNAL_URL = Pattern.compile("https?://[^\\s)\\]]+");

    @Override
    public MiddlewareResult beforeFinish(FinishContext context) {
        if (!appliesToSearchAgent(context)) {
            return MiddlewareResult.continueProcessing();
        }
        CitationScan scan = scan(context.output());
        if (scan.hasInvalidCitationMarker()) {
            return retryOrFail(context,
                    "Citations must use [citation:Title](http(s)://url) with a non-empty title.");
        }
        if (scan.hasBareExternalUrl()) {
            return retryOrFail(context,
                    "External URLs must appear only inside [citation:Title](http(s)://url) citations.");
        }
        if (requiresCitation(context, scan) && scan.validCitationCount() == 0) {
            return retryOrFail(context,
                    "External source results require at least one citation.");
        }
        return MiddlewareResult.continueProcessing();
    }

    private CitationScan scan(String output) {
        String text = output == null ? "" : output;
        Matcher citationMatcher = VALID_CITATION.matcher(text);
        int validCount = 0;
        while (citationMatcher.find()) {
            if (!citationMatcher.group(1).trim().isBlank()) {
                validCount++;
            }
        }
        String withoutValidCitations = citationMatcher.replaceAll("");
        boolean invalidCitationMarker = withoutValidCitations.contains("[citation:");
        boolean bareExternalUrl = EXTERNAL_URL.matcher(withoutValidCitations).find();
        boolean mentionsExternalUrl = EXTERNAL_URL.matcher(text).find();
        return new CitationScan(validCount, invalidCitationMarker, bareExternalUrl, mentionsExternalUrl);
    }

    private boolean requiresCitation(FinishContext context, CitationScan scan) {
        return scan.mentionsExternalUrl() || hasSuccessfulWebToolResult(context);
    }

    private boolean hasSuccessfulWebToolResult(FinishContext context) {
        return context.state().messages().stream()
                .filter(ToolExecutionResultMessage.class::isInstance)
                .map(ToolExecutionResultMessage.class::cast)
                .filter(message -> WEB_SEARCH_TOOL.equals(message.toolName()) || WEB_FETCH_TOOL.equals(message.toolName()))
                .map(ToolExecutionResultMessage::text)
                .anyMatch(this::isSuccessfulToolResult);
    }

    private boolean isSuccessfulToolResult(String text) {
        return text != null && text.lines()
                .map(String::trim)
                .anyMatch(line -> "status: success".equalsIgnoreCase(line));
    }

    private record CitationScan(
            int validCitationCount,
            boolean hasInvalidCitationMarker,
            boolean hasBareExternalUrl,
            boolean mentionsExternalUrl
    ) {
    }
}
