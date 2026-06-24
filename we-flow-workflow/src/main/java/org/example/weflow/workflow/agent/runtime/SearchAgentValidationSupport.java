package org.example.weflow.workflow.agent.runtime;

import org.example.weflow.workflow.agent.DefaultAgentSpecs;

abstract class SearchAgentValidationSupport {

    static final String FAILURE_CODE = "SEARCH_AGENT_OUTPUT_VALIDATION_FAILED";
    private static final int DEFAULT_MAX_RETRIES = 1;

    private final int maxRetries;

    SearchAgentValidationSupport() {
        this(DEFAULT_MAX_RETRIES);
    }

    SearchAgentValidationSupport(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    boolean appliesToSearchAgent(FinishContext context) {
        return DefaultAgentSpecs.SEARCH_AGENT_CODE.equals(context.runContext().spec().definition().code());
    }

    MiddlewareResult retryOrFail(FinishContext context, String reason) {
        if (context.state().outputValidationRetryCount() < maxRetries) {
            return MiddlewareResult.retry(retryFeedback(reason));
        }
        return MiddlewareResult.fail(FAILURE_CODE,
                "Search agent output validation failed after "
                        + maxRetries
                        + " retry. "
                        + reason);
    }

    private String retryFeedback(String reason) {
        return """
                Search agent output validation failed: %s

                Please revise your previous answer to satisfy the required output format:
                1. A brief summary of what was investigated
                2. Key findings or results
                3. Relevant file paths, symbols, data, or artifacts inspected
                4. Recommended next steps for the lead agent
                5. Issues, gaps, or uncertainty encountered, if any
                6. Citations using [citation:Title](URL), or None when no external sources were used
                """.formatted(reason);
    }
}
