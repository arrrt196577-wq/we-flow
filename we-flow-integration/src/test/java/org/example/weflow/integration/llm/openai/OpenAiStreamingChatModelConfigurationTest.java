package org.example.weflow.integration.llm.openai;

import static org.assertj.core.api.Assertions.assertThat;

import dev.langchain4j.model.chat.StreamingChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class OpenAiStreamingChatModelConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(OpenAiStreamingChatModelConfiguration.class)
            .withPropertyValues(
                    "we-flow.llm.default-provider=test",
                    "we-flow.llm.providers.test.type=openai-compatible",
                    "we-flow.llm.providers.test.base-url=http://localhost:8080/v1",
                    "we-flow.llm.providers.test.api-key=test-key",
                    "we-flow.llm.providers.test.model-name=test-model"
            );

    @Test
    void bindsReasoningProperties() {
        contextRunner
                .withPropertyValues(
                        "we-flow.llm.providers.test.reasoning-effort=medium",
                        "we-flow.llm.providers.test.return-thinking=true",
                        "we-flow.llm.providers.test.send-thinking=true",
                        "we-flow.llm.providers.test.thinking-field-name=reasoning_content"
                )
                .run(context -> {
                    WeFlowLlmProperties.ProviderProperties provider = context.getBean(WeFlowLlmProperties.class)
                            .defaultProviderProperties();

                    assertThat(provider.reasoningEffort()).isEqualTo("medium");
                    assertThat(provider.returnThinking()).isTrue();
                    assertThat(provider.sendThinking()).isTrue();
                    assertThat(provider.thinkingFieldName()).isEqualTo("reasoning_content");
                    assertThat(context).hasSingleBean(StreamingChatModel.class);
                });
    }

    @Test
    void missingReasoningPropertiesStillCreatesModel() {
        contextRunner.run(context -> {
            WeFlowLlmProperties.ProviderProperties provider = context.getBean(WeFlowLlmProperties.class)
                    .defaultProviderProperties();

            assertThat(provider.reasoningEffort()).isNull();
            assertThat(provider.returnThinking()).isNull();
            assertThat(provider.sendThinking()).isNull();
            assertThat(provider.thinkingFieldName()).isNull();
            assertThat(context).hasSingleBean(StreamingChatModel.class);
        });
    }
}
