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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.bsc.langgraph4j.langchain4j.tool.LC4jToolService;
import org.example.weflow.agent.subagent.InMemorySubAgentRegistry;
import org.example.weflow.agent.subagent.SimpleTaskSubAgentExecutor;
import org.example.weflow.agent.tool.AgentTool;
import org.example.weflow.agent.tool.TaskDelegationTool;
import org.example.weflow.agent.tool.WorkspaceFileTools;
import org.example.weflow.core.service.IChatService;
import org.example.weflow.core.service.dto.ChatStreamChunk;
import org.example.weflow.core.service.dto.ChatStreamRequest;
import org.example.weflow.core.service.impl.ChatServiceImpl;
import org.example.weflow.core.workspace.DefaultWorkspaceService;
import org.example.weflow.core.workspace.WorkspaceProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

class LangGraph4jChatHarnessServiceTest {

    @TempDir
    Path workspaceRoot;

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(StreamingChatModel.class, RecordingChatModel::new)
            .withBean(LC4jToolService.class, () -> LC4jToolService.builder().build())
            .withUserConfiguration(
                    ChatServiceImpl.class,
                    LangGraph4jChatHarnessConfiguration.class,
                    LangGraph4jChatHarnessService.class
            );

    @Test
    void defaultConfigurationUsesDirectChatService() {
        contextRunner.run(context -> assertThat(context)
                .hasSingleBean(IChatService.class)
                .getBean(IChatService.class)
                .isInstanceOf(ChatServiceImpl.class));
    }

    @Test
    void langGraph4jEngineUsesHarnessService() {
        contextRunner
                .withPropertyValues("we-flow.chat.engine=langgraph4j")
                .run(context -> assertThat(context)
                        .hasSingleBean(IChatService.class)
                        .getBean(IChatService.class)
                        .isInstanceOf(LangGraph4jChatHarnessService.class));
    }

    @Test
    void sameConversationIdRestoresPreviousCheckpointMessages() {
        RecordingChatModel chatModel = new RecordingChatModel();
        LangGraph4jChatHarnessService service = service(chatModel);

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
        LangGraph4jChatHarnessService service = service(chatModel);

        stream(service, new ChatStreamRequest("my name is zhang san", "conversation-1", null));
        String isolated = stream(service, new ChatStreamRequest("what is my name?", "conversation-2", null));

        assertThat(isolated).isEqualTo("call-2 messages=user:what is my name?");
        assertThat(chatModel.requests().get(1).messages()).hasSize(2);
        assertThat(chatModel.requests().get(1).messages().getFirst()).isInstanceOf(SystemMessage.class);
    }

    @Test
    void systemPromptShouldNotExposeWebSearchWhenToolIsUnavailable() {
        RecordingChatModel chatModel = new RecordingChatModel();
        LangGraph4jChatHarnessService service = service(chatModel);

        stream(service, new ChatStreamRequest("联网查一下", "conversation-no-search-tool", null));

        assertThat(systemPrompt(chatModel.requests().getFirst()))
                .contains("Web search is not available")
                .doesNotContain("web_search");
    }

    @Test
    void systemPromptShouldExposeWebSearchWhenToolIsAvailable() {
        RecordingChatModel chatModel = new RecordingChatModel();
        LangGraph4jChatHarnessService service = service(chatModel, webSearchToolService("status: success\ntotalResults: 0\n"));

        stream(service, new ChatStreamRequest("联网查一下", "conversation-search-tool", null));

        assertThat(systemPrompt(chatModel.requests().getFirst()))
                .contains("Use web_search")
                .contains("include source links");
    }

    @Test
    void systemPromptShouldNotExposeDelegateTaskWhenToolIsUnavailable() {
        RecordingChatModel chatModel = new RecordingChatModel();
        LangGraph4jChatHarnessService service = service(chatModel);

        stream(service, new ChatStreamRequest("delegate work", "conversation-no-delegate-tool", null));

        assertThat(systemPrompt(chatModel.requests().getFirst()))
                .doesNotContain("delegate_task")
                .doesNotContain("simple_task_subagent");
    }

    @Test
    void systemPromptShouldExposeDelegateTaskWhenToolIsAvailable() {
        RecordingChatModel chatModel = new RecordingChatModel();
        LangGraph4jChatHarnessService service = service(chatModel, delegateTaskToolService());

        stream(service, new ChatStreamRequest("delegate work", "conversation-delegate-tool", null));

        assertThat(systemPrompt(chatModel.requests().getFirst()))
                .contains("Use delegate_task")
                .contains("simple_task_subagent")
                .contains("Do not invent other subagent codes");
    }

    @Test
    void emitsFinalThinkingBeforeAnswerContent() {
        ToolCallingChatModel chatModel = new ToolCallingChatModel(List.of(AiMessage.builder()
                .text("final answer")
                .thinking("reasoning trail")
                .build()));
        LangGraph4jChatHarnessService service = service(chatModel);

        List<ChatStreamChunk> chunks = streamChunks(service, new ChatStreamRequest("hello", "conversation-thinking", null));

        assertThat(chunks).extracting(ChatStreamChunk::type)
                .containsExactly(ChatStreamChunk.Type.REASONING, ChatStreamChunk.Type.CONTENT);
        assertThat(chunks).extracting(ChatStreamChunk::content)
                .containsExactly("reasoning trail", "final answer");
    }

    @Test
    void missingConversationIdReportsError() {
        LangGraph4jChatHarnessService service = service(new RecordingChatModel());
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
        LangGraph4jChatHarnessService service = service(chatModel, fileToolService());

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
        LangGraph4jChatHarnessService service = service(chatModel);

        String response = stream(service, new ChatStreamRequest("联网查一下丹尼尔", "conversation-search-disabled", null));

        assertThat(response).contains("搜索功能未启用");
        assertThat(chatModel.requests()).hasSize(1);
    }

    @Test
    void unsupportedToolShouldReturnUnavailableToolMessage() {
        ToolCallingChatModel chatModel = new ToolCallingChatModel(List.of(
                AiMessage.from(toolRequest("tool-call-missing", "missing_tool", "{}"))
        ));
        LangGraph4jChatHarnessService service = service(chatModel);

        String response = stream(service, new ChatStreamRequest("use missing tool", "conversation-missing-tool", null));

        assertThat(response).contains("请求的工具不可用：missing_tool");
        assertThat(chatModel.requests()).hasSize(1);
    }

    @Test
    void webSearchErrorShouldStopBeforeSecondModelCall() {
        ToolCallingChatModel chatModel = new ToolCallingChatModel(List.of(
                AiMessage.from(toolRequest("tool-call-search", "web_search", "{\"query\":\"丹尼尔\"}"))
        ));
        LangGraph4jChatHarnessService service = service(chatModel, webSearchToolService("""
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
        LangGraph4jChatHarnessService service = service(chatModel, webSearchToolService("""
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
    void delegateTaskSuccessShouldSendToolResultBackToModel() {
        ToolCallingChatModel chatModel = new ToolCallingChatModel(List.of(
                AiMessage.from(toolRequest("tool-call-delegate", "delegate_task",
                        "{\"subAgentCode\":\"simple_task_subagent\",\"taskType\":\"general_task\","
                                + "\"objective\":\"verify delegation\",\"input\":\"abc\"}")),
                AiMessage.from("delegated response")
        ));
        LangGraph4jChatHarnessService service = service(chatModel, delegateTaskToolService());

        String response = stream(service, new ChatStreamRequest("delegate this", "conversation-delegate-success", null));

        assertThat(response).isEqualTo("delegated response");
        assertThat(chatModel.requests()).hasSize(2);
        assertThat(toolResultText(chatModel.requests().get(1)))
                .contains("status: success")
                .contains("subAgent: simple_task_subagent")
                .contains("output: accepted task");
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
        LangGraph4jChatHarnessService service = service(chatModel, fileToolService());

        String response = stream(service, new ChatStreamRequest("read docs/readme.md", "conversation-more", null));

        assertThat(response).isEqualTo("read both chunks");
        assertThat(chatModel.requests()).hasSize(3);
        assertThat(toolResultText(chatModel.requests().get(1))).contains("hasMore: true", "nextStartLine: 2");
        assertThat(toolResultText(chatModel.requests().get(2))).contains("1 | one", "2 | two");
    }

    private LangGraph4jChatHarnessService service(StreamingChatModel chatModel) {
        return service(chatModel, LC4jToolService.builder().build());
    }

    private LangGraph4jChatHarnessService service(StreamingChatModel chatModel, LC4jToolService toolService) {
        return new LangGraph4jChatHarnessService(new ChatHarnessGraphFactory(chatModel, toolService));
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

    private LC4jToolService delegateTaskToolService() {
        return LC4jToolService.builder()
                .toolsFromObject(new TaskDelegationTool(new InMemorySubAgentRegistry(List.of(
                        new SimpleTaskSubAgentExecutor()
                ))))
                .build();
    }

    private String stream(LangGraph4jChatHarnessService service, ChatStreamRequest request) {
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

    private List<ChatStreamChunk> streamChunks(LangGraph4jChatHarnessService service, ChatStreamRequest request) {
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
