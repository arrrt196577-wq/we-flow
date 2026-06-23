package org.example.weflow.workflow.agent.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.example.weflow.core.agent.AgentDefinition;
import org.example.weflow.core.agent.AgentRuntimeLimits;
import org.example.weflow.core.agent.AgentSpec;
import org.example.weflow.core.agent.AgentToolPolicy;
import org.example.weflow.core.agent.AgentType;
import org.example.weflow.workflow.agent.AgentThreadState;
import org.junit.jupiter.api.Test;

class MiddlewareManagerTest {

    @Test
    void emptyModelChainCallsTerminal() {
        MiddlewareManager manager = MiddlewareManager.empty();

        AiMessage result = manager.aroundModel(modelContext(), context -> AiMessage.from("terminal"));

        assertThat(result.text()).isEqualTo("terminal");
    }

    @Test
    void modelAroundMiddlewareUsesOnionOrder() {
        List<String> order = new ArrayList<>();
        WeflowMiddleware first = new WeflowMiddleware() {
            @Override
            public AiMessage aroundModel(ModelCallContext context, ModelCall next) {
                order.add("first-before");
                AiMessage result = next.call(context);
                order.add("first-after");
                return result;
            }
        };
        WeflowMiddleware second = new WeflowMiddleware() {
            @Override
            public AiMessage aroundModel(ModelCallContext context, ModelCall next) {
                order.add("second-before");
                AiMessage result = next.call(context);
                order.add("second-after");
                return result;
            }
        };
        MiddlewareManager manager = new MiddlewareManager(List.of(first, second));

        AiMessage result = manager.aroundModel(modelContext(), context -> {
            order.add("terminal");
            return AiMessage.from("done");
        });

        assertThat(result.text()).isEqualTo("done");
        assertThat(order).containsExactly(
                "first-before",
                "second-before",
                "terminal",
                "second-after",
                "first-after"
        );
    }

    @Test
    void beforeModelStopsAtFirstShortCircuitResult() {
        Map<String, Object> update = Map.of("answer", "short");
        WeflowMiddleware pass = new WeflowMiddleware() {
        };
        WeflowMiddleware stop = new WeflowMiddleware() {
            @Override
            public MiddlewareResult beforeModel(ModelCallContext context) {
                return MiddlewareResult.shortCircuit(update);
            }
        };
        WeflowMiddleware later = new WeflowMiddleware() {
            @Override
            public MiddlewareResult beforeModel(ModelCallContext context) {
                return MiddlewareResult.fail("SHOULD_NOT_RUN", "later");
            }
        };
        MiddlewareManager manager = new MiddlewareManager(List.of(pass, stop, later));

        MiddlewareResult result = manager.beforeModel(modelContext()).orElseThrow();

        assertThat(result.type()).isEqualTo(MiddlewareResult.Type.SHORT_CIRCUIT);
        assertThat(result.update()).containsEntry("answer", "short");
    }

    @Test
    void failureUpdateUsesControlledFailureTextExactly() {
        MiddlewareManager manager = MiddlewareManager.empty();

        Map<String, Object> update = manager.failureUpdate("TEST_CODE", "test message");

        String expected = "status: error\n"
                + "code: TEST_CODE\n"
                + "message:\n"
                + "test message\n";
        assertThat(update)
                .containsEntry(AgentThreadState.FAILURE_CODE, "TEST_CODE")
                .containsEntry(AgentThreadState.FAILURE_MESSAGE, "test message")
                .containsEntry(AgentThreadState.CURRENT_ASSISTANT_MESSAGE, expected)
                .containsEntry(AgentThreadState.CURRENT_ASSISTANT_THINKING, "");
        assertThat(((List<?>) update.get(MessagesState.MESSAGES_STATE)).getFirst())
                .isEqualTo(AiMessage.from(expected));
    }

    private ModelCallContext modelContext() {
        return new ModelCallContext(
                new AgentRunContext(agentSpec(), "thread-1"),
                new AgentThreadState(Map.of()),
                ChatRequest.builder()
                        .messages(List.of(UserMessage.from("hello")))
                        .build(),
                Duration.ofSeconds(5),
                chunk -> {
                }
        );
    }

    private AgentSpec agentSpec() {
        return new AgentSpec(
                new AgentDefinition("test_agent", "Test Agent", AgentType.LEAD, "Test agent.", true),
                "",
                AgentToolPolicy.none(),
                AgentRuntimeLimits.lead(3, Duration.ofSeconds(5), null)
        );
    }
}
