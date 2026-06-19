package org.example.weflow.integration.llm.openai;

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WeFlowLlmProperties.class)
public class OpenAiStreamingChatModelConfiguration {

    private static final Duration DEFAULT_STREAMING_TIMEOUT = Duration.ofSeconds(600);

    @Bean
    @ConditionalOnMissingBean(StreamingChatModel.class)
    public StreamingChatModel streamingChatModel(WeFlowLlmProperties properties) {
        WeFlowLlmProperties.ProviderProperties providerProperties = properties.defaultProviderProperties();
        providerProperties.validateOpenAiCompatible(properties.defaultProvider());

        OpenAiStreamingChatModelBuilder builder = OpenAiStreamingChatModel.builder()
                .baseUrl(providerProperties.baseUrl())
                .apiKey(providerProperties.apiKey())
                .modelName(providerProperties.modelName())
                .temperature(providerProperties.temperature())
                .timeout(DEFAULT_STREAMING_TIMEOUT);

        //  推理
        if (hasText(providerProperties.reasoningEffort())) {
            builder.reasoningEffort(providerProperties.reasoningEffort());
        }
        if (providerProperties.returnThinking() != null) {
            builder.returnThinking(providerProperties.returnThinking());
        }
        if (providerProperties.sendThinking() != null) {
            if (hasText(providerProperties.thinkingFieldName())) {
                builder.sendThinking(providerProperties.sendThinking(), providerProperties.thinkingFieldName());
            } else {
                builder.sendThinking(providerProperties.sendThinking());
            }
        }

        return builder.build();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
