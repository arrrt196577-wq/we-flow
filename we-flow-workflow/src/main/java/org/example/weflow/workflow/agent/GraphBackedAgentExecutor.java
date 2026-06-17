package org.example.weflow.workflow.agent;

import java.util.Map;
import java.util.function.Supplier;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.RunnableConfig;
import org.example.weflow.core.agent.AgentContext;
import org.example.weflow.core.agent.AgentDefinition;
import org.example.weflow.core.agent.AgentExecutor;
import org.example.weflow.core.agent.AgentResult;
import org.example.weflow.core.agent.AgentSpec;
import org.example.weflow.core.agent.AgentTask;
import org.example.weflow.workflow.chat.ChatHarnessState;
import org.springframework.beans.factory.ObjectProvider;

public class GraphBackedAgentExecutor implements AgentExecutor {

    private final AgentSpec spec;
    private final Supplier<AgentGraphFactory> graphFactorySupplier;
    private volatile CompiledGraph<ChatHarnessState> graph;

    public GraphBackedAgentExecutor(AgentSpec spec, ObjectProvider<AgentGraphFactory> graphFactoryProvider) {
        this(spec, graphFactoryProvider::getObject);
    }

    public GraphBackedAgentExecutor(AgentSpec spec, AgentGraphFactory graphFactory) {
        this(spec, () -> graphFactory);
    }

    private GraphBackedAgentExecutor(AgentSpec spec, Supplier<AgentGraphFactory> graphFactorySupplier) {
        this.spec = spec;
        this.graphFactorySupplier = graphFactorySupplier;
    }

    @Override
    public AgentDefinition definition() {
        return spec.definition();
    }

    @Override
    public AgentResult execute(AgentTask task, AgentContext context) {
        try {
            ChatHarnessState finalState = graph().invoke(
                    Map.of(ChatHarnessState.CURRENT_USER_MESSAGE, taskMessage(task, context)),
                    RunnableConfig.builder()
                            .threadId(task.taskId())
                            .build()
            ).orElseThrow(() -> new IllegalStateException("Agent graph did not produce a final state."));
            return AgentResult.success(task.taskId(), spec.definition().code(), finalState.currentAssistantMessage());
        } catch (RuntimeException e) {
            return AgentResult.failed(
                    task.taskId(),
                    spec.definition().code(),
                    "AGENT_EXECUTION_FAILED",
                    e.getMessage()
            );
        }
    }

    private CompiledGraph<ChatHarnessState> graph() {
        CompiledGraph<ChatHarnessState> current = graph;
        if (current == null) {
            synchronized (this) {
                current = graph;
                if (current == null) {
                    current = graphFactorySupplier.get().create(spec);
                    graph = current;
                }
            }
        }
        return current;
    }

    private String taskMessage(AgentTask task, AgentContext context) {
        return """
                parentAgentCode: %s
                taskId: %s
                taskType: %s
                objective: %s
                input:
                %s
                """.formatted(
                context == null ? "" : safe(context.parentAgentCode()),
                safe(task.taskId()),
                safe(task.taskType()),
                safe(task.objective()),
                task.input() == null ? "" : task.input()
        );
    }

    private String safe(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
    }
}
