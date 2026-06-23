package org.example.weflow.workflow.chat;

import static org.assertj.core.api.Assertions.assertThat;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.bsc.langgraph4j.langchain4j.tool.LC4jToolService;
import org.example.weflow.agent.subagent.InMemorySubAgentRegistry;
import org.example.weflow.agent.subagent.SubAgentRegistry;
import org.example.weflow.agent.tool.AgentTool;
import org.example.weflow.agent.tool.TaskDelegationTool;
import org.example.weflow.agent.tool.WorkspaceFileTools;
import org.example.weflow.core.agent.AgentContext;
import org.example.weflow.core.agent.AgentDefinition;
import org.example.weflow.core.agent.AgentExecutor;
import org.example.weflow.core.agent.AgentRuntimeLimits;
import org.example.weflow.core.agent.AgentResult;
import org.example.weflow.core.agent.AgentStatus;
import org.example.weflow.core.agent.AgentSpec;
import org.example.weflow.core.agent.AgentTask;
import org.example.weflow.core.agent.AgentType;
import org.example.weflow.core.service.IChatService;
import org.example.weflow.core.service.dto.ChatStreamChunk;
import org.example.weflow.core.service.dto.ChatStreamRequest;
import org.example.weflow.core.service.impl.ChatServiceImpl;
import org.example.weflow.core.workspace.DefaultWorkspaceService;
import org.example.weflow.core.workspace.WorkspaceProperties;
import org.example.weflow.workflow.agent.AgentGraphFactory;
import org.example.weflow.workflow.agent.DefaultAgentSpecs;
import org.example.weflow.workflow.agent.GraphBackedAgentExecutor;
import org.example.weflow.workflow.agent.ImplementPlaceholderAgentExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class LangGraph4jAgentChatServiceTest {

    @TempDir
    Path workspaceRoot;

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(StreamingChatModel.class, RecordingChatModel::new)
            .withBean(LC4jToolService.class, () -> LC4jToolService.builder().build())
            .withUserConfiguration(
                    ChatServiceImpl.class,
                    LangGraph4jAgentRuntimeConfiguration.class,
                    LangGraph4jAgentChatService.class
            );

    @Test
    void defaultConfigurationUsesDirectChatService() {
        contextRunner.run(context -> assertThat(context)
                .hasSingleBean(IChatService.class)
                .getBean(IChatService.class)
                .isInstanceOf(ChatServiceImpl.class));
    }

    @Test
    void langGraph4jEngineUsesAgentChatService() {
        contextRunner
                .withPropertyValues("we-flow.chat.engine=langgraph4j")
                .run(context -> assertThat(context)
                        .hasSingleBean(IChatService.class)
                        .getBean(IChatService.class)
                        .isInstanceOf(LangGraph4jAgentChatService.class));
    }

    @Test
    void agentRuntimeLimitsShouldUseDefaults() {
        contextRunner
                .withPropertyValues("we-flow.chat.engine=langgraph4j")
                .run(context -> {
                    AgentRuntimeLimits leadLimits = context.getBean("leadAgentSpec", AgentSpec.class).runtimeLimits();
                    AgentRuntimeLimits searchLimits = context.getBean("searchAgentSpec", AgentSpec.class).runtimeLimits();

                    assertThat(leadLimits.maxLoops()).isEqualTo(100);
                    assertThat(leadLimits.llmTimeout()).isEqualTo(Duration.ofSeconds(600));
                    assertThat(leadLimits.overallTimeout()).isNull();
                    assertThat(leadLimits.leadToolCallLimit().warningThreshold()).isEqualTo(30);
                    assertThat(leadLimits.leadToolCallLimit().stopThreshold()).isEqualTo(50);
                    assertThat(searchLimits.maxLoops()).isEqualTo(50);
                    assertThat(searchLimits.llmTimeout()).isEqualTo(Duration.ofSeconds(600));
                    assertThat(searchLimits.overallTimeout()).isEqualTo(Duration.ofSeconds(900));
                    assertThat(searchLimits.leadToolCallLimit()).isNull();
                });
    }

    @Test
    void agentRuntimeLimitsShouldComeFromProperties() {
        contextRunner
                .withPropertyValues(
                        "we-flow.chat.engine=langgraph4j",
                        "we-flow.agent.max-loops.lead=3",
                        "we-flow.agent.max-loops.subagent=4",
                        "we-flow.agent.llm-timeout=5s",
                        "we-flow.agent.subagent-timeout=7s",
                        "we-flow.agent.lead-tool-call-limit.warning-threshold=2",
                        "we-flow.agent.lead-tool-call-limit.stop-threshold=3"
                )
                .run(context -> {
                    AgentRuntimeLimits leadLimits = context.getBean("leadAgentSpec", AgentSpec.class).runtimeLimits();
                    AgentRuntimeLimits searchLimits = context.getBean("searchAgentSpec", AgentSpec.class).runtimeLimits();

                    assertThat(leadLimits.maxLoops()).isEqualTo(3);
                    assertThat(leadLimits.llmTimeout()).isEqualTo(Duration.ofSeconds(5));
                    assertThat(leadLimits.leadToolCallLimit().warningThreshold()).isEqualTo(2);
                    assertThat(leadLimits.leadToolCallLimit().stopThreshold()).isEqualTo(3);
                    assertThat(searchLimits.maxLoops()).isEqualTo(4);
                    assertThat(searchLimits.llmTimeout()).isEqualTo(Duration.ofSeconds(5));
                    assertThat(searchLimits.overallTimeout()).isEqualTo(Duration.ofSeconds(7));
                });
    }

    @Test
    void sameConversationIdRestoresPreviousCheckpointMessages() {
        RecordingChatModel chatModel = new RecordingChatModel();
        LangGraph4jAgentChatService service = service(chatModel);

        String first = stream(service, new ChatStreamRequest("my name is zhang san", "conversation-1", null));
        String second = stream(service, new ChatStreamRequest("what is my name?", "conversation-1", null));

        assertThat(first).isEqualTo("call-1 messages=user:my name is zhang san");
        assertThat(second).isEqualTo("call-2 messages=user:my name is zhang san|assistant:call-1 messages=user:my name is zhang san|user:what is my name?");
        assertThat(chatModel.requests()).hasSize(2);
        assertThat(chatModel.requests().get(1).messages()).hasSize(4);
        assertThat(chatModel.requests().get(1).messages().getFirst()).isInstanceOf(SystemMessage.class);
    }

    @Test
    void differentConversationIdsDoNotShareCheckpointMessages() {
        RecordingChatModel chatModel = new RecordingChatModel();
        LangGraph4jAgentChatService service = service(chatModel);

        stream(service, new ChatStreamRequest("my name is zhang san", "conversation-1", null));
        String isolated = stream(service, new ChatStreamRequest("what is my name?", "conversation-2", null));

        assertThat(isolated).isEqualTo("call-2 messages=user:what is my name?");
        assertThat(chatModel.requests().get(1).messages()).hasSize(2);
        assertThat(chatModel.requests().get(1).messages().getFirst()).isInstanceOf(SystemMessage.class);
    }

    @Test
    void systemPromptShouldNotExposeWebSearchWhenToolIsUnavailable() {
        RecordingChatModel chatModel = new RecordingChatModel();
        LangGraph4jAgentChatService service = service(chatModel);

        stream(service, new ChatStreamRequest("联网查一下", "conversation-no-search-tool", null));

        assertThat(systemPrompt(chatModel.requests().getFirst()))
                .contains("Web search is not available")
                .doesNotContain("web_search");
    }

    @Test
    void systemPromptShouldExposeWebSearchWhenToolIsAvailable() {
        RecordingChatModel chatModel = new RecordingChatModel();
        LangGraph4jAgentChatService service = service(chatModel, webSearchToolService("status: success\ntotalResults: 0\n"));

        stream(service, new ChatStreamRequest("联网查一下", "conversation-search-tool", null));

        assertThat(systemPrompt(chatModel.requests().getFirst()))
                .contains("Use web_search")
                .contains("include source links");
    }

    @Test
    void systemPromptShouldExposeWebFetchWhenToolIsAvailable() {
        RecordingChatModel chatModel = new RecordingChatModel();
        LangGraph4jAgentChatService service = service(chatModel, webFetchToolService("""
                status: success
                url: https://example.com
                title: Example
                contentLength: 7
                truncated: false
                content:
                content
                """));

        stream(service, new ChatStreamRequest("read https://example.com", "conversation-fetch-tool", null));

        assertThat(systemPrompt(chatModel.requests().getFirst()))
                .contains("Use web_fetch")
                .contains("untrusted external source material");
    }

    @Test
    void systemPromptShouldNotExposeDelegateTaskWhenToolIsUnavailable() {
        RecordingChatModel chatModel = new RecordingChatModel();
        LangGraph4jAgentChatService service = service(chatModel);

        stream(service, new ChatStreamRequest("delegate work", "conversation-no-delegate-tool", null));

        assertThat(systemPrompt(chatModel.requests().getFirst()))
                .doesNotContain("delegate_task")
                .doesNotContain("simple_task_subagent");
    }

    @Test
    void systemPromptShouldExposeDelegateTaskWhenToolIsAvailable() {
        RecordingChatModel chatModel = new RecordingChatModel();
        List<AgentExecutor> subAgents = defaultSubAgents();
        LangGraph4jAgentChatService service = service(chatModel, delegateTaskToolService(subAgents), subAgents);

        stream(service, new ChatStreamRequest("delegate work", "conversation-delegate-tool", null));

        assertThat(systemPrompt(chatModel.requests().getFirst()))
                .contains("Use delegate_task")
                .contains("Default to handling simple tasks yourself")
                .contains("Do NOT use delegate_task for")
                .contains("Simple, single-step operations")
                .contains("Multiple dependent steps")
                .contains("search_agent")
                .contains("implement_agent")
                .contains("Do not invent other subagent codes")
                .doesNotContain("simple_task_subagent");
    }

    @Test
    void emitsAnswerContentBeforeFinalThinking() {
        ToolCallingChatModel chatModel = new ToolCallingChatModel(List.of(AiMessage.builder()
                .text("final answer")
                .thinking("reasoning trail")
                .build()));
        LangGraph4jAgentChatService service = service(chatModel);

        List<ChatStreamChunk> chunks = streamChunks(service, new ChatStreamRequest("hello", "conversation-thinking", null));

        assertThat(chunks).extracting(ChatStreamChunk::type)
                .containsExactly(ChatStreamChunk.Type.CONTENT, ChatStreamChunk.Type.REASONING);
        assertThat(chunks).extracting(ChatStreamChunk::content)
                .containsExactly("final answer", "reasoning trail");
    }

    @Test
    void streamsLeadContentAsMultipleChunksWithoutFinalDuplicate() {
        LangGraph4jAgentChatService service = service(new MultiChunkChatModel("lead ", "answer"));

        List<ChatStreamChunk> chunks = streamChunks(service, new ChatStreamRequest("hello", "conversation-multi-chunk", null));

        assertThat(chunks).extracting(ChatStreamChunk::type)
                .containsExactly(ChatStreamChunk.Type.CONTENT, ChatStreamChunk.Type.CONTENT);
        assertThat(chunks).extracting(ChatStreamChunk::content)
                .containsExactly("lead ", "answer");
    }

    @Test
    void fallsBackToFinalContentWhenNoContentWasStreamed() {
        ToolCallingChatModel chatModel = new ToolCallingChatModel(List.of(
                AiMessage.from(toolRequest("tool-call-missing", "missing_tool", "{}"))
        ));
        LangGraph4jAgentChatService service = service(chatModel);

        List<ChatStreamChunk> chunks = streamChunks(service, new ChatStreamRequest("use missing tool", "conversation-fallback", null));

        assertThat(chunks).extracting(ChatStreamChunk::type)
                .containsExactly(ChatStreamChunk.Type.CONTENT);
        assertThat(chunks.getFirst().content()).contains("missing_tool");
    }

    @Test
    void graphBackedSubAgentContentDoesNotStreamAsLeadContent() {
        ToolCallingChatModel chatModel = new ToolCallingChatModel(List.of(
                AiMessage.from(toolRequest("tool-call-delegate", "delegate_task",
                        "{\"subAgentCode\":\"search_agent\",\"taskType\":\"general_task\","
                                + "\"objective\":\"verify delegation\",\"input\":\"abc\"}")),
                AiMessage.from("subagent should stay internal"),
                AiMessage.from("lead final")
        ));
        MutableSubAgentRegistry subAgentRegistry = new MutableSubAgentRegistry();
        LC4jToolService toolService = LC4jToolService.builder()
                .toolsFromObject(new TaskDelegationTool(subAgentRegistry))
                .build();
        AgentGraphFactory graphFactory = new AgentGraphFactory(chatModel, toolService);
        AgentExecutor subAgent = new GraphBackedAgentExecutor(DefaultAgentSpecs.searchAgentSpec(), graphFactory);
        subAgentRegistry.register(subAgent);
        LangGraph4jAgentChatService service = service(
                graphFactory,
                toolService,
                List.of(subAgent),
                DefaultAgentSpecs.defaultLeadRuntimeLimits(),
                Runnable::run
        );

        List<ChatStreamChunk> chunks = streamChunks(service, new ChatStreamRequest("delegate", "conversation-subagent-isolation", null));

        assertThat(chunks).extracting(ChatStreamChunk::type)
                .containsExactly(ChatStreamChunk.Type.CONTENT);
        assertThat(chunks.getFirst().content()).isEqualTo("lead final");
        assertThat(chunks).extracting(ChatStreamChunk::content)
                .doesNotContain("subagent should stay internal");
    }

    @Test
    void streamReturnsBeforeModelCompletesAndEmitsPartialContent() throws Exception {
        DelayedStreamingChatModel chatModel = new DelayedStreamingChatModel();
        LC4jToolService toolService = LC4jToolService.builder().build();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        LangGraph4jAgentChatService service = service(
                new AgentGraphFactory(chatModel, toolService),
                toolService,
                List.of(),
                DefaultAgentSpecs.defaultLeadRuntimeLimits(),
                executor
        );
        List<ChatStreamChunk> chunks = new ArrayList<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicBoolean completed = new AtomicBoolean(false);
        CountDownLatch serviceCompleted = new CountDownLatch(1);

        try {
            service.stream(
                    new ChatStreamRequest("hello", "conversation-async-stream", null),
                    chunks::add,
                    error::set,
                    () -> {
                        completed.set(true);
                        serviceCompleted.countDown();
                    }
            );

            assertThat(chatModel.partialSent().await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(completed.get()).isFalse();
            assertThat(chunks).extracting(ChatStreamChunk::content)
                    .containsExactly("partial");

            chatModel.allowComplete();

            assertThat(chatModel.completed().await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(serviceCompleted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(error.get()).isNull();
            assertThat(completed.get()).isTrue();
            assertThat(chunks).extracting(ChatStreamChunk::content)
                    .containsExactly("partial");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void missingConversationIdReportsError() {
        LangGraph4jAgentChatService service = service(new RecordingChatModel());
        AtomicReference<Throwable> error = new AtomicReference<>();

        service.stream(
                new ChatStreamRequest("hello", null, null),
                chunk -> {
                },
                error::set,
                () -> {
                }
        );

        assertThat(error.get())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("conversationId is required");
    }

    @Test
    void executesToolCallAndSendsToolResultBackToModel() throws IOException {
        Files.createDirectories(workspaceRoot.resolve("docs"));
        Files.writeString(workspaceRoot.resolve("docs/readme.md"), "hello\n", StandardCharsets.UTF_8);
        ToolCallingChatModel chatModel = new ToolCallingChatModel(List.of(
                AiMessage.from(toolRequest("tool-call-1", "read_file",
                        "{\"path\":\"docs/readme.md\",\"startLine\":1,\"maxLines\":10}")),
                AiMessage.from("file says hello")
        ));
        LangGraph4jAgentChatService service = service(chatModel, fileToolService());

        String response = stream(service, new ChatStreamRequest("read docs/readme.md", "conversation-tools", null));

        assertThat(response).isEqualTo("file says hello");
        assertThat(chatModel.requests()).hasSize(2);
        assertThat(chatModel.requests().get(0).toolSpecifications())
                .extracting(specification -> specification.name())
                .contains("find_files", "read_file", "list_dir");
        assertThat(chatModel.requests().get(1).messages())
                .anySatisfy(message -> {
                    assertThat(message).isInstanceOf(ToolExecutionResultMessage.class);
                    assertThat(((ToolExecutionResultMessage) message).text()).contains("1 | hello");
                });
    }

    @Test
    void unsupportedWebSearchToolShouldReturnSearchDisabledMessage() {
        ToolCallingChatModel chatModel = new ToolCallingChatModel(List.of(
                AiMessage.from(toolRequest("tool-call-search", "web_search", "{\"query\":\"丹尼尔\"}"))
        ));
        LangGraph4jAgentChatService service = service(chatModel);

        String response = stream(service, new ChatStreamRequest("联网查一下丹尼尔", "conversation-search-disabled", null));

        assertThat(response).contains("搜索功能未启用");
        assertThat(chatModel.requests()).hasSize(1);
    }

    @Test
    void unsupportedWebFetchToolShouldReturnFetchDisabledMessage() {
        ToolCallingChatModel chatModel = new ToolCallingChatModel(List.of(
                AiMessage.from(toolRequest("tool-call-fetch", "web_fetch", "{\"url\":\"https://example.com\"}"))
        ));
        LangGraph4jAgentChatService service = service(chatModel);

        String response = stream(service, new ChatStreamRequest("read https://example.com", "conversation-fetch-disabled", null));

        assertThat(response).contains("网页读取功能未启用");
        assertThat(response).contains("we-flow.fetch.enabled=true");
        assertThat(chatModel.requests()).hasSize(1);
    }

    @Test
    void unsupportedToolShouldReturnUnavailableToolMessage() {
        ToolCallingChatModel chatModel = new ToolCallingChatModel(List.of(
                AiMessage.from(toolRequest("tool-call-missing", "missing_tool", "{}"))
        ));
        LangGraph4jAgentChatService service = service(chatModel);

        String response = stream(service, new ChatStreamRequest("use missing tool", "conversation-missing-tool", null));

        assertThat(response).contains("请求的工具不可用：missing_tool");
        assertThat(chatModel.requests()).hasSize(1);
    }

    @Test
    void webSearchErrorShouldStopBeforeSecondModelCall() {
        ToolCallingChatModel chatModel = new ToolCallingChatModel(List.of(
                AiMessage.from(toolRequest("tool-call-search", "web_search", "{\"query\":\"丹尼尔\"}"))
        ));
        LangGraph4jAgentChatService service = service(chatModel, webSearchToolService("""
                status: error
                code: SEARCH_FAILED
                message: DuckDuckGo search failed
                """));

        String response = stream(service, new ChatStreamRequest("联网查一下丹尼尔", "conversation-search-failed", null));

        assertThat(response).contains("联网搜索失败：DuckDuckGo search failed");
        assertThat(response).contains("不会继续生成联网结论");
        assertThat(chatModel.requests()).hasSize(1);
    }

    @Test
    void webSearchSuccessShouldSendToolResultBackToModel() {
        ToolCallingChatModel chatModel = new ToolCallingChatModel(List.of(
                AiMessage.from(toolRequest("tool-call-search", "web_search", "{\"query\":\"丹尼尔\"}")),
                AiMessage.from("answer with source")
        ));
        LangGraph4jAgentChatService service = service(chatModel, webSearchToolService("""
                status: success
                query: 丹尼尔
                totalResults: 1
                results:
                1. title: Daniel
                   url: https://example.com/daniel
                   snippet: Daniel is a name.
                """));

        String response = stream(service, new ChatStreamRequest("联网查一下丹尼尔", "conversation-search-success", null));

        assertThat(response).isEqualTo("answer with source");
        assertThat(chatModel.requests()).hasSize(2);
        assertThat(toolResultText(chatModel.requests().get(1))).contains("status: success", "https://example.com/daniel");
    }

    @Test
    void webFetchErrorShouldStopBeforeSecondModelCall() {
        ToolCallingChatModel chatModel = new ToolCallingChatModel(List.of(
                AiMessage.from(toolRequest("tool-call-fetch", "web_fetch", "{\"url\":\"https://example.com\"}"))
        ));
        LangGraph4jAgentChatService service = service(chatModel, webFetchToolService("""
                status: error
                code: FETCH_FAILED
                message: Jina fetch failed
                """));

        String response = stream(service, new ChatStreamRequest("read https://example.com", "conversation-fetch-failed", null));

        assertThat(response).contains("网页读取失败：Jina fetch failed");
        assertThat(response).contains("不会继续基于该页面生成结论");
        assertThat(chatModel.requests()).hasSize(1);
    }

    @Test
    void webFetchSuccessShouldSendToolResultBackToModel() {
        ToolCallingChatModel chatModel = new ToolCallingChatModel(List.of(
                AiMessage.from(toolRequest("tool-call-fetch", "web_fetch", "{\"url\":\"https://example.com\"}")),
                AiMessage.from("answer from fetched page")
        ));
        LangGraph4jAgentChatService service = service(chatModel, webFetchToolService("""
                status: success
                url: https://example.com
                title: Example
                contentLength: 7
                truncated: false
                content:
                content
                """));

        String response = stream(service, new ChatStreamRequest("read https://example.com", "conversation-fetch-success", null));

        assertThat(response).isEqualTo("answer from fetched page");
        assertThat(chatModel.requests()).hasSize(2);
        assertThat(toolResultText(chatModel.requests().get(1))).contains("status: success", "https://example.com");
    }

    @Test
    void delegateTaskSuccessShouldSendToolResultBackToModel() {
        ToolCallingChatModel chatModel = new ToolCallingChatModel(List.of(
                AiMessage.from(toolRequest("tool-call-delegate", "delegate_task",
                        "{\"subAgentCode\":\"search_agent\",\"taskType\":\"general_task\","
                                + "\"objective\":\"verify delegation\",\"input\":\"abc\"}")),
                AiMessage.from("delegated response")
        ));
        List<AgentExecutor> subAgents = defaultSubAgents();
        LangGraph4jAgentChatService service = service(chatModel, delegateTaskToolService(subAgents), subAgents);

        String response = stream(service, new ChatStreamRequest("delegate this", "conversation-delegate-success", null));

        assertThat(response).isEqualTo("delegated response");
        assertThat(chatModel.requests()).hasSize(2);
        assertThat(toolResultText(chatModel.requests().get(1)))
                .contains("status: success")
                .contains("subAgent: search_agent")
                .contains("traceId:")
                .contains("startedAt:")
                .contains("completedAt:")
                .contains("result:\nsearch agent output");
    }

    @Test
    void delegateTaskToImplementAgentShouldReturnPlaceholderError() {
        ToolCallingChatModel chatModel = new ToolCallingChatModel(List.of(
                AiMessage.from(toolRequest("tool-call-implement", "delegate_task",
                        "{\"subAgentCode\":\"implement_agent\",\"taskType\":\"implementation\","
                                + "\"objective\":\"edit files\",\"input\":\"{}\"}")),
                AiMessage.from("implementation unavailable")
        ));
        List<AgentExecutor> subAgents = defaultSubAgents();
        LangGraph4jAgentChatService service = service(chatModel, delegateTaskToolService(subAgents), subAgents);

        String response = stream(service, new ChatStreamRequest("delegate implementation", "conversation-implement-placeholder", null));

        assertThat(response).isEqualTo("implementation unavailable");
        assertThat(toolResultText(chatModel.requests().get(1)))
                .contains("status: error")
                .contains("subAgent: implement_agent")
                .contains("traceId:")
                .contains("code: IMPLEMENT_AGENT_NOT_ENABLED")
                .contains("startedAt:")
                .contains("completedAt:")
                .contains("尚未启用执行能力");
    }

    @Test
    void searchAgentShouldSeeOnlySearchAndReadOnlyFileTools() {
        RecordingChatModel chatModel = new RecordingChatModel();
        LC4jToolService toolService = LC4jToolService.builder()
                .toolsFromObject(new WorkspaceFileTools(
                        new DefaultWorkspaceService(new WorkspaceProperties(workspaceRoot.toString()))))
                .toolsFromObject(new TestWebSearchTool("status: success\ntotalResults: 0\n"))
                .toolsFromObject(new TestWebFetchTool("status: success\nurl: https://example.com\ncontent:\ncontent\n"))
                .toolsFromObject(new TaskDelegationTool(new InMemorySubAgentRegistry(defaultSubAgents())))
                .build();
        AgentExecutor searchAgent = new GraphBackedAgentExecutor(
                DefaultAgentSpecs.searchAgentSpec(),
                new AgentGraphFactory(chatModel, toolService)
        );

        AgentResult result = searchAgent.execute(
                new AgentTask("search-task-1", "research", "inspect workspace", ""),
                new AgentContext("lead_agent", "trace-search-test")
        );

        assertThat(result.output()).contains("call-1 messages=user:");
        assertThat(result.traceId()).isEqualTo("trace-search-test");
        assertThat(result.startedAt()).isNotNull();
        assertThat(result.completedAt()).isNotNull();
        assertThat(toolNames(chatModel.requests().getFirst()))
                .containsExactlyInAnyOrder("find_files", "read_file", "list_dir", "web_search", "web_fetch");
        assertThat(systemPrompt(chatModel.requests().getFirst()))
                .contains("read-only research subagent")
                .contains("web_fetch")
                .contains("Do NOT ask for clarification")
                .contains("Recommended next steps for the lead agent")
                .contains("Citations: Use `[citation:Title](URL)` format");
    }

    @Test
    void modelCanContinueReadingWhenReadFileHasMoreContent() throws IOException {
        Files.createDirectories(workspaceRoot.resolve("docs"));
        Files.writeString(workspaceRoot.resolve("docs/readme.md"), "one\ntwo\n", StandardCharsets.UTF_8);
        ToolCallingChatModel chatModel = new ToolCallingChatModel(List.of(
                AiMessage.from(toolRequest("tool-call-1", "read_file",
                        "{\"path\":\"docs/readme.md\",\"startLine\":1,\"maxLines\":1}")),
                AiMessage.from(toolRequest("tool-call-2", "read_file",
                        "{\"path\":\"docs/readme.md\",\"startLine\":2,\"maxLines\":1}")),
                AiMessage.from("read both chunks")
        ));
        LangGraph4jAgentChatService service = service(chatModel, fileToolService());

        String response = stream(service, new ChatStreamRequest("read docs/readme.md", "conversation-more", null));

        assertThat(response).isEqualTo("read both chunks");
        assertThat(chatModel.requests()).hasSize(3);
        assertThat(toolResultText(chatModel.requests().get(1))).contains("hasMore: true", "nextStartLine: 2");
        assertThat(toolResultText(chatModel.requests().get(2))).contains("1 | one", "2 | two");
    }

    @Test
    void leadMaxLoopsShouldCountEveryLlmCall() throws IOException {
        Files.createDirectories(workspaceRoot.resolve("docs"));
        Files.writeString(workspaceRoot.resolve("docs/readme.md"), "hello\n", StandardCharsets.UTF_8);
        ToolCallingChatModel chatModel = new ToolCallingChatModel(List.of(
                AiMessage.from(toolRequest("tool-call-1", "read_file",
                        "{\"path\":\"docs/readme.md\",\"startLine\":1,\"maxLines\":10}")),
                AiMessage.from("should not be called")
        ));
        AgentRuntimeLimits limits = AgentRuntimeLimits.lead(
                1,
                Duration.ofSeconds(5),
                new AgentRuntimeLimits.ToolCallLimit(30, 50)
        );
        LangGraph4jAgentChatService service = service(chatModel, fileToolService(), List.of(), limits);

        String response = stream(service, new ChatStreamRequest("read docs/readme.md", "conversation-max-loop", null));

        assertThat(response).contains("status: error")
                .contains("AGENT_MAX_LOOPS_EXCEEDED");
        assertThat(chatModel.requests()).hasSize(1);
    }

    @Test
    void subAgentMaxLoopsShouldReturnFailedResult() throws IOException {
        Files.createDirectories(workspaceRoot.resolve("docs"));
        Files.writeString(workspaceRoot.resolve("docs/readme.md"), "hello\n", StandardCharsets.UTF_8);
        ToolCallingChatModel chatModel = new ToolCallingChatModel(List.of(
                AiMessage.from(toolRequest("tool-call-1", "read_file",
                        "{\"path\":\"docs/readme.md\",\"startLine\":1,\"maxLines\":10}")),
                AiMessage.from("should not be called")
        ));
        AgentExecutor searchAgent = new GraphBackedAgentExecutor(
                DefaultAgentSpecs.searchAgentSpec(AgentRuntimeLimits.subagent(
                        1,
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(30)
                )),
                new AgentGraphFactory(chatModel, fileToolService())
        );

        AgentResult result = searchAgent.execute(
                new AgentTask("search-task-max-loop", "research", "read file", ""),
                new AgentContext("lead_agent", "trace-max-loop")
        );

        assertThat(result.status()).isEqualTo(AgentStatus.FAILED);
        assertThat(result.errorCode()).isEqualTo("AGENT_MAX_LOOPS_EXCEEDED");
        assertThat(result.output()).isNull();
        assertThat(chatModel.requests()).hasSize(1);
    }

    @Test
    void leadLlmTimeoutShouldReturnNormalErrorContent() {
        LangGraph4jAgentChatService service = service(
                new HangingChatModel(),
                LC4jToolService.builder().build(),
                List.of(),
                AgentRuntimeLimits.lead(
                        3,
                        Duration.ofMillis(10),
                        new AgentRuntimeLimits.ToolCallLimit(30, 50)
                )
        );

        String response = stream(service, new ChatStreamRequest("hello", "conversation-llm-timeout", null));

        assertThat(response).contains("status: error")
                .contains("AGENT_LLM_TIMEOUT");
    }

    @Test
    void subAgentLlmTimeoutShouldReturnFailedResult() {
        AgentExecutor searchAgent = new GraphBackedAgentExecutor(
                DefaultAgentSpecs.searchAgentSpec(AgentRuntimeLimits.subagent(
                        3,
                        Duration.ofMillis(10),
                        Duration.ofSeconds(5)
                )),
                new AgentGraphFactory(new HangingChatModel(), LC4jToolService.builder().build())
        );

        AgentResult result = searchAgent.execute(
                new AgentTask("search-task-llm-timeout", "research", "hang", ""),
                new AgentContext("lead_agent", "trace-llm-timeout")
        );

        assertThat(result.status()).isEqualTo(AgentStatus.FAILED);
        assertThat(result.errorCode()).isEqualTo("AGENT_LLM_TIMEOUT");
        assertThat(result.output()).isNull();
    }

    @Test
    void subAgentOverallTimeoutShouldIncludeToolExecution() {
        ToolCallingChatModel chatModel = new ToolCallingChatModel(List.of(
                AiMessage.from(toolRequest("tool-call-slow", "web_search", "{\"query\":\"slow\"}")),
                AiMessage.from("should not be called")
        ));
        LC4jToolService toolService = LC4jToolService.builder()
                .toolsFromObject(new SlowWebSearchTool(Duration.ofMillis(80)))
                .build();
        AgentExecutor searchAgent = new GraphBackedAgentExecutor(
                DefaultAgentSpecs.searchAgentSpec(AgentRuntimeLimits.subagent(
                        3,
                        Duration.ofSeconds(5),
                        Duration.ofMillis(20)
                )),
                new AgentGraphFactory(chatModel, toolService)
        );

        AgentResult result = searchAgent.execute(
                new AgentTask("search-task-overall-timeout", "research", "slow tool", ""),
                new AgentContext("lead_agent", "trace-overall-timeout")
        );

        assertThat(result.status()).isEqualTo(AgentStatus.FAILED);
        assertThat(result.errorCode()).isEqualTo("SUB_AGENT_TIMEOUT");
        assertThat(result.output()).isNull();
        assertThat(chatModel.requests()).hasSize(1);
    }

    @Test
    void leadToolWarningShouldBeInjectedAtWarningThreshold() throws IOException {
        Files.createDirectories(workspaceRoot.resolve("docs"));
        Files.writeString(workspaceRoot.resolve("docs/readme.md"), "hello\n", StandardCharsets.UTF_8);
        ToolCallingChatModel chatModel = new ToolCallingChatModel(List.of(
                AiMessage.from(toolRequest("tool-call-1", "read_file",
                        "{\"path\":\"docs/readme.md\",\"startLine\":1,\"maxLines\":10}")),
                AiMessage.from(toolRequest("tool-call-2", "read_file",
                        "{\"path\":\"docs/readme.md\",\"startLine\":1,\"maxLines\":10}")),
                AiMessage.from("done")
        ));
        AgentRuntimeLimits limits = AgentRuntimeLimits.lead(
                5,
                Duration.ofSeconds(5),
                new AgentRuntimeLimits.ToolCallLimit(2, 4)
        );
        LangGraph4jAgentChatService service = service(chatModel, fileToolService(), List.of(), limits);

        String response = stream(service, new ChatStreamRequest("read twice", "conversation-tool-warning", null));

        assertThat(response).isEqualTo("done");
        assertThat(toolResultText(chatModel.requests().get(2)))
                .contains("LEAD_TOOL_CALL_WARNING")
                .contains("read_file");
    }

    @Test
    void leadToolHardStopShouldPreventThresholdCall() throws IOException {
        Files.createDirectories(workspaceRoot.resolve("docs"));
        Files.writeString(workspaceRoot.resolve("docs/readme.md"), "hello\n", StandardCharsets.UTF_8);
        ToolCallingChatModel chatModel = new ToolCallingChatModel(List.of(
                AiMessage.from(toolRequest("tool-call-1", "read_file",
                        "{\"path\":\"docs/readme.md\",\"startLine\":1,\"maxLines\":10}")),
                AiMessage.from(toolRequest("tool-call-2", "read_file",
                        "{\"path\":\"docs/readme.md\",\"startLine\":1,\"maxLines\":10}")),
                AiMessage.from(toolRequest("tool-call-3", "read_file",
                        "{\"path\":\"docs/readme.md\",\"startLine\":1,\"maxLines\":10}")),
                AiMessage.from("should not be called")
        ));
        AgentRuntimeLimits limits = AgentRuntimeLimits.lead(
                10,
                Duration.ofSeconds(5),
                new AgentRuntimeLimits.ToolCallLimit(2, 3)
        );
        LangGraph4jAgentChatService service = service(chatModel, fileToolService(), List.of(), limits);

        String response = stream(service, new ChatStreamRequest("read too much", "conversation-tool-stop", null));

        assertThat(response).contains("status: error")
                .contains("LEAD_TOOL_CALL_LIMIT_EXCEEDED")
                .contains("read_file");
        assertThat(chatModel.requests()).hasSize(3);
    }

    @Test
    void subAgentToolCallsShouldNotCountAgainstLeadToolFrequency() throws IOException {
        Files.createDirectories(workspaceRoot.resolve("docs"));
        Files.writeString(workspaceRoot.resolve("docs/readme.md"), "hello\n", StandardCharsets.UTF_8);
        ToolCallingChatModel chatModel = new ToolCallingChatModel(List.of(
                AiMessage.from(toolRequest("tool-call-delegate", "delegate_task",
                        "{\"subAgentCode\":\"search_agent\",\"taskType\":\"general_task\","
                                + "\"objective\":\"verify delegation\",\"input\":\"abc\"}")),
                AiMessage.from(toolRequest("tool-call-read", "read_file",
                        "{\"path\":\"docs/readme.md\",\"startLine\":1,\"maxLines\":10}")),
                AiMessage.from("done")
        ));
        AgentExecutor subAgent = successfulSubAgentWithInternalReads(
                DefaultAgentSpecs.SEARCH_AGENT_CODE,
                new WorkspaceFileTools(new DefaultWorkspaceService(new WorkspaceProperties(workspaceRoot.toString())))
        );
        LC4jToolService toolService = LC4jToolService.builder()
                .toolsFromObject(new WorkspaceFileTools(
                        new DefaultWorkspaceService(new WorkspaceProperties(workspaceRoot.toString()))))
                .toolsFromObject(new TaskDelegationTool(new InMemorySubAgentRegistry(List.of(subAgent))))
                .build();
        AgentRuntimeLimits limits = AgentRuntimeLimits.lead(
                10,
                Duration.ofSeconds(5),
                new AgentRuntimeLimits.ToolCallLimit(1, 2)
        );
        LangGraph4jAgentChatService service = service(chatModel, toolService, List.of(subAgent), limits);

        String response = stream(service, new ChatStreamRequest("delegate then read", "conversation-subagent-counts", null));

        assertThat(response).isEqualTo("done");
        assertThat(toolResultText(chatModel.requests().get(2)))
                .contains("LEAD_TOOL_CALL_WARNING")
                .contains("read_file");
    }

    private LangGraph4jAgentChatService service(StreamingChatModel chatModel) {
        return service(chatModel, LC4jToolService.builder().build());
    }

    private LangGraph4jAgentChatService service(StreamingChatModel chatModel, LC4jToolService toolService) {
        return service(chatModel, toolService, List.of());
    }

    private LangGraph4jAgentChatService service(
            StreamingChatModel chatModel,
            LC4jToolService toolService,
            List<AgentExecutor> subAgents
    ) {
        return service(chatModel, toolService, subAgents, DefaultAgentSpecs.defaultLeadRuntimeLimits());
    }

    private LangGraph4jAgentChatService service(
            StreamingChatModel chatModel,
            LC4jToolService toolService,
            List<AgentExecutor> subAgents,
            AgentRuntimeLimits leadRuntimeLimits
    ) {
        return service(
                new AgentGraphFactory(chatModel, toolService),
                toolService,
                subAgents,
                leadRuntimeLimits,
                Runnable::run
        );
    }

    private LangGraph4jAgentChatService service(
            AgentGraphFactory graphFactory,
            LC4jToolService toolService,
            List<AgentExecutor> subAgents,
            AgentRuntimeLimits leadRuntimeLimits,
            Executor executor
    ) {
        return new LangGraph4jAgentChatService(
                graphFactory,
                DefaultAgentSpecs.leadAgentSpec(
                        subAgents.stream()
                                .map(AgentExecutor::definition)
                                .toList(),
                        toolNames(toolService),
                        leadRuntimeLimits
                ),
                executor
        );
    }

    private LC4jToolService fileToolService() {
        return LC4jToolService.builder()
                .toolsFromObject(new WorkspaceFileTools(
                        new DefaultWorkspaceService(new WorkspaceProperties(workspaceRoot.toString()))))
                .build();
    }

    private LC4jToolService webSearchToolService(String result) {
        return LC4jToolService.builder()
                .toolsFromObject(new TestWebSearchTool(result))
                .build();
    }

    private LC4jToolService webFetchToolService(String result) {
        return LC4jToolService.builder()
                .toolsFromObject(new TestWebFetchTool(result))
                .build();
    }

    private LC4jToolService delegateTaskToolService(List<AgentExecutor> subAgents) {
        return LC4jToolService.builder()
                .toolsFromObject(new TaskDelegationTool(new InMemorySubAgentRegistry(subAgents)))
                .build();
    }

    private List<AgentExecutor> defaultSubAgents() {
        return List.of(
                successfulSubAgent(DefaultAgentSpecs.SEARCH_AGENT_CODE, "search agent output"),
                new ImplementPlaceholderAgentExecutor()
        );
    }

    private AgentExecutor successfulSubAgent(String code, String output) {
        return new AgentExecutor() {
            @Override
            public AgentDefinition definition() {
                return new AgentDefinition(code, code, AgentType.SUB, "test subagent", true);
            }

            @Override
            public AgentResult execute(AgentTask task, AgentContext context) {
                Instant startedAt = Instant.now();
                return AgentResult.success(task.taskId(), context.traceId(), code, output, startedAt, Instant.now());
            }
        };
    }

    private AgentExecutor successfulSubAgentWithInternalReads(String code, WorkspaceFileTools fileTools) {
        return new AgentExecutor() {
            @Override
            public AgentDefinition definition() {
                return new AgentDefinition(code, code, AgentType.SUB, "test subagent", true);
            }

            @Override
            public AgentResult execute(AgentTask task, AgentContext context) {
                for (int i = 0; i < 5; i++) {
                    fileTools.readFile("docs/readme.md", 1, 1);
                }
                Instant startedAt = Instant.now();
                return AgentResult.success(task.taskId(), context.traceId(), code,
                        "internal reads completed", startedAt, Instant.now());
            }
        };
    }

    private String stream(LangGraph4jAgentChatService service, ChatStreamRequest request) {
        StringBuilder chunks = new StringBuilder();
        AtomicReference<Throwable> error = new AtomicReference<>();

        service.stream(request, chunk -> {
            if (chunk.type() == ChatStreamChunk.Type.CONTENT) {
                chunks.append(chunk.content());
            }
        }, error::set, () -> {
        });

        assertThat(error.get()).isNull();
        return chunks.toString();
    }

    private List<ChatStreamChunk> streamChunks(LangGraph4jAgentChatService service, ChatStreamRequest request) {
        List<ChatStreamChunk> chunks = new ArrayList<>();
        AtomicReference<Throwable> error = new AtomicReference<>();

        service.stream(request, chunks::add, error::set, () -> {
        });

        assertThat(error.get()).isNull();
        return chunks;
    }

    private ToolExecutionRequest toolRequest(String id, String name, String arguments) {
        return ToolExecutionRequest.builder()
                .id(id)
                .name(name)
                .arguments(arguments)
                .build();
    }

    private String toolResultText(ChatRequest request) {
        return request.messages().stream()
                .filter(ToolExecutionResultMessage.class::isInstance)
                .map(ToolExecutionResultMessage.class::cast)
                .map(ToolExecutionResultMessage::text)
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
    }

    private String systemPrompt(ChatRequest request) {
        return request.messages().stream()
                .filter(SystemMessage.class::isInstance)
                .map(SystemMessage.class::cast)
                .map(SystemMessage::text)
                .findFirst()
                .orElse("");
    }

    private Set<String> toolNames(LC4jToolService toolService) {
        return toolService.toolSpecifications().stream()
                .map(specification -> specification.name())
                .collect(Collectors.toSet());
    }

    private Set<String> toolNames(ChatRequest request) {
        return request.toolSpecifications().stream()
                .map(specification -> specification.name())
                .collect(Collectors.toSet());
    }

    private static final class TestWebSearchTool implements AgentTool {

        private final String result;

        private TestWebSearchTool(String result) {
            this.result = result;
        }

        @Tool(name = "web_search", value = "Search the web for test data.")
        public String webSearch(@P("Search query") String query) {
            return result;
        }
    }

    private static final class TestWebFetchTool implements AgentTool {

        private final String result;

        private TestWebFetchTool(String result) {
            this.result = result;
        }

        @Tool(name = "web_fetch", value = "Fetch web content for test data.")
        public String webFetch(@P("URL") String url) {
            return result;
        }
    }

    private static final class SlowWebSearchTool implements AgentTool {

        private final Duration delay;

        private SlowWebSearchTool(Duration delay) {
            this.delay = delay;
        }

        @Tool(name = "web_search", value = "Slow web search test tool.")
        public String webSearch(@P("Search query") String query) {
            try {
                Thread.sleep(delay.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "status: error\nmessage: interrupted\n";
            }
            return "status: success\nresult: slow\n";
        }
    }

    private static final class RecordingChatModel implements StreamingChatModel {

        private final List<ChatRequest> requests = new ArrayList<>();

        @Override
        public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
            requests.add(request);
            String response = "call-" + requests.size() + " messages=" + messagesAsText(request.messages());
            handler.onPartialResponse(response);
            handler.onCompleteResponse(ChatResponse.builder()
                    .aiMessage(AiMessage.from(response))
                    .build());
        }

        List<ChatRequest> requests() {
            return requests;
        }

        private String messagesAsText(List<ChatMessage> messages) {
            return messages.stream()
                    .filter(message -> !(message instanceof SystemMessage))
                    .map(this::messageAsText)
                    .reduce((left, right) -> left + "|" + right)
                    .orElse("");
        }

        private String messageAsText(ChatMessage message) {
            if (message instanceof UserMessage userMessage) {
                return "user:" + userMessage.singleText();
            }
            if (message instanceof AiMessage aiMessage) {
                return "assistant:" + aiMessage.text();
            }
            if (message instanceof ToolExecutionResultMessage toolMessage) {
                return "tool:" + toolMessage.text();
            }
            return message.type().name().toLowerCase() + ":" + message.toString();
        }
    }

    private static final class HangingChatModel implements StreamingChatModel {

        @Override
        public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
            // Intentionally never completes; runtime timeouts should handle this.
        }
    }

    private static final class MultiChunkChatModel implements StreamingChatModel {

        private final List<String> chunks;

        private MultiChunkChatModel(String... chunks) {
            this.chunks = List.of(chunks);
        }

        @Override
        public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
            chunks.forEach(handler::onPartialResponse);
            handler.onCompleteResponse(ChatResponse.builder()
                    .aiMessage(AiMessage.from(String.join("", chunks)))
                    .build());
        }
    }

    private static final class DelayedStreamingChatModel implements StreamingChatModel {

        private final CountDownLatch partialSent = new CountDownLatch(1);
        private final CountDownLatch allowComplete = new CountDownLatch(1);
        private final CountDownLatch completed = new CountDownLatch(1);

        @Override
        public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
            handler.onPartialResponse("partial");
            partialSent.countDown();
            try {
                if (!allowComplete.await(2, TimeUnit.SECONDS)) {
                    handler.onError(new IllegalStateException("timed out waiting for test completion"));
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                handler.onError(e);
                return;
            }
            handler.onCompleteResponse(ChatResponse.builder()
                    .aiMessage(AiMessage.from("partial"))
                    .build());
            completed.countDown();
        }

        CountDownLatch partialSent() {
            return partialSent;
        }

        void allowComplete() {
            allowComplete.countDown();
        }

        CountDownLatch completed() {
            return completed;
        }
    }

    private static final class MutableSubAgentRegistry implements SubAgentRegistry {

        private final List<AgentExecutor> executors = new ArrayList<>();

        private void register(AgentExecutor executor) {
            executors.add(executor);
        }

        @Override
        public Optional<AgentExecutor> findByCode(String code) {
            return executors.stream()
                    .filter(executor -> executor.definition().code().equals(code))
                    .findFirst();
        }

        @Override
        public List<AgentDefinition> listDefinitions() {
            return executors.stream()
                    .map(AgentExecutor::definition)
                    .toList();
        }
    }

    private static final class ToolCallingChatModel implements StreamingChatModel {

        private final List<AiMessage> responses;
        private final List<ChatRequest> requests = new ArrayList<>();

        private ToolCallingChatModel(List<AiMessage> responses) {
            this.responses = responses;
        }

        @Override
        public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
            requests.add(request);
            AiMessage response = responses.get(requests.size() - 1);
            if (!response.hasToolExecutionRequests()) {
                handler.onPartialResponse(response.text());
            }
            handler.onCompleteResponse(ChatResponse.builder()
                    .aiMessage(response)
                    .build());
        }

        List<ChatRequest> requests() {
            return requests;
        }
    }
}
