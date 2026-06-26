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
    void emptyTurnInitializationChainCallsTerminal() {
        MiddlewareManager manager = MiddlewareManager.empty();

        Map<String, Object> update = manager.aroundTurnInitialization(
                turnInitializationContext(),
                context -> Map.of("base", true)
        );

        assertThat(update).containsEntry("base", true);
    }

    @Test
    void turnInitializationAroundMiddlewareUsesOnionOrder() {
        List<String> order = new ArrayList<>();
        WeflowMiddleware first = new WeflowMiddleware() {
            @Override
            public Map<String, Object> aroundTurnInitialization(
                    TurnInitializationContext context,
                    TurnInitializationCall next
            ) {
                order.add("first-before");
                Map<String, Object> result = next.call(context);
                order.add("first-after");
                return result;
            }
        };
        WeflowMiddleware second = new WeflowMiddleware() {
            @Override
            public Map<String, Object> aroundTurnInitialization(
                    TurnInitializationContext context,
                    TurnInitializationCall next
            ) {
                order.add("second-before");
                Map<String, Object> result = next.call(context);
                order.add("second-after");
                return result;
            }
        };
        MiddlewareManager manager = new MiddlewareManager(List.of(first, second));

        Map<String, Object> update = manager.aroundTurnInitialization(turnInitializationContext(), context -> {
            order.add("terminal");
            return Map.of("done", true);
        });

        assertThat(update).containsEntry("done", true);
        assertThat(order).containsExactly(
                "first-before",
                "second-before",
                "terminal",
                "second-after",
                "first-after"
        );
    }

    @Test
    void runtimeLimitMiddlewareInitializesOverallDeadlineWhenConfigured() {
        RuntimeLimitMiddleware middleware = new RuntimeLimitMiddleware();
        long before = System.currentTimeMillis();

        Map<String, Object> update = middleware.aroundTurnInitialization(
                new TurnInitializationContext(
                        new AgentRunContext(agentSpecWithOverallTimeout(), "thread-1"),
                        new AgentThreadState(Map.of())
                ),
                context -> Map.of("base", true)
        );

        long after = System.currentTimeMillis();
        assertThat(update)
                .containsEntry("base", true)
                .containsKey(AgentThreadState.DEADLINE_EPOCH_MILLIS);
        assertThat((Long) update.get(AgentThreadState.DEADLINE_EPOCH_MILLIS))
                .isBetween(before + 30_000, after + 30_000);
    }

    @Test
    void runtimeLimitMiddlewareDoesNotInitializeDeadlineWhenUnconfigured() {
        RuntimeLimitMiddleware middleware = new RuntimeLimitMiddleware();

        Map<String, Object> update = middleware.aroundTurnInitialization(
                turnInitializationContext(),
                context -> Map.of("base", true)
        );

        assertThat(update).containsOnly(Map.entry("base", true));
    }

    @Test
    void leadToolCallLimitMiddlewareInitializesToolCallCounts() {
        LeadToolCallLimitMiddleware middleware = new LeadToolCallLimitMiddleware();

        Map<String, Object> update = middleware.aroundTurnInitialization(
                turnInitializationContext(),
                context -> Map.of("base", true)
        );

        assertThat(update)
                .containsEntry("base", true)
                .containsEntry(AgentThreadState.LEAD_TOOL_CALL_COUNTS, Map.of());
    }

    @Test
    void emptyRunChainCallsTerminal() {
        MiddlewareManager manager = MiddlewareManager.empty();
        AgentThreadState expected = new AgentThreadState(Map.of());

        AgentThreadState result = manager.aroundRun(runContext(), context -> expected);

        assertThat(result).isSameAs(expected);
    }

    @Test
    void finishAroundMiddlewareUsesOnionOrder() {
        List<String> order = new ArrayList<>();
        WeflowMiddleware first = new WeflowMiddleware() {
            @Override
            public Map<String, Object> aroundFinish(FinishContext context, FinishCall next) {
                order.add("first-before");
                Map<String, Object> result = next.call(context);
                order.add("first-after");
                return result;
            }
        };
        WeflowMiddleware second = new WeflowMiddleware() {
            @Override
            public Map<String, Object> aroundFinish(FinishContext context, FinishCall next) {
                order.add("second-before");
                Map<String, Object> result = next.call(context);
                order.add("second-after");
                return result;
            }
        };
        MiddlewareManager manager = new MiddlewareManager(List.of(first, second));

        Map<String, Object> result = manager.aroundFinish(finishContext(), context -> {
            order.add("terminal");
            return Map.of("done", true);
        });

        assertThat(result).containsEntry("done", true);
        assertThat(order).containsExactly(
                "first-before",
                "second-before",
                "terminal",
                "second-after",
                "first-after"
        );
    }

    @Test
    void afterFinishStopsAtFirstNonContinueResult() {
        WeflowMiddleware pass = new WeflowMiddleware() {
        };
        WeflowMiddleware stop = new WeflowMiddleware() {
            @Override
            public MiddlewareResult afterFinish(FinishContext context) {
                return MiddlewareResult.fail("STOP", "stop here");
            }
        };
        WeflowMiddleware later = new WeflowMiddleware() {
            @Override
            public MiddlewareResult afterFinish(FinishContext context) {
                return MiddlewareResult.fail("SHOULD_NOT_RUN", "later");
            }
        };
        MiddlewareManager manager = new MiddlewareManager(List.of(pass, stop, later));

        MiddlewareResult result = manager.afterFinish(finishContext()).orElseThrow();

        assertThat(result.type()).isEqualTo(MiddlewareResult.Type.FAIL);
        assertThat(result.failureCode()).isEqualTo("STOP");
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

    private AgentRunContext runContext() {
        return new AgentRunContext(agentSpec(), "thread-1");
    }

    private FinishContext finishContext() {
        return new FinishContext(runContext(), new AgentThreadState(Map.of()), "output");
    }

    private TurnInitializationContext turnInitializationContext() {
        return new TurnInitializationContext(runContext(), new AgentThreadState(Map.of()));
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

    private AgentSpec agentSpecWithOverallTimeout() {
        return new AgentSpec(
                new AgentDefinition("test_subagent", "Test Subagent", AgentType.SUB, "Test subagent.", true),
                "",
                AgentToolPolicy.none(),
                AgentRuntimeLimits.subagent(3, Duration.ofSeconds(5), Duration.ofSeconds(30))
        );
    }
}
