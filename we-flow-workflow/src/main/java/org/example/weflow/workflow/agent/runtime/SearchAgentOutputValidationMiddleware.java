package org.example.weflow.workflow.agent.runtime;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SearchAgentOutputValidationMiddleware extends SearchAgentValidationSupport
        implements WeflowMiddleware {

    @Override
    public MiddlewareResult beforeFinish(FinishContext context) {
        if (!appliesToSearchAgent(context)) {
            return MiddlewareResult.continueProcessing();
        }
        return invalidReason(context.output())
                .map(reason -> retryOrFail(context, reason))
                .orElseGet(MiddlewareResult::continueProcessing);
    }

    private Optional<String> invalidReason(String output) {
        if (output == null || output.isBlank()) {
            return Optional.of("Output is blank.");
        }

        int contentStart = 0;
        for (int section = 1; section <= 6; section++) {
            Matcher current = sectionHeader(section).matcher(output);
            if (!current.find(contentStart)) {
                return Optional.of("Missing section " + section + ".");
            }
            int nextStart = output.length();
            if (section < 6) {
                Matcher next = sectionHeader(section + 1).matcher(output);
                if (!next.find(current.end())) {
                    return Optional.of("Missing section " + (section + 1) + ".");
                }
                nextStart = next.start();
            }
            String content = output.substring(current.end(), nextStart).trim();
            if (content.isBlank()) {
                return Optional.of("Section " + section + " is empty.");
            }
            contentStart = nextStart;
        }
        return Optional.empty();
    }

    private Pattern sectionHeader(int section) {
        return Pattern.compile("(?m)^\\s*" + section + "\\.\\s*");
    }
}
