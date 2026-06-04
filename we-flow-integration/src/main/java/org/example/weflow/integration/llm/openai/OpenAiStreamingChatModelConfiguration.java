package org.example.weflow.integration.llm.openai;

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WeFlowLlmProperties.class)
public class OpenAiStreamingChatModelConfiguration {

    @Bean
    @ConditionalOnMissingBean(StreamingChatModel.class)
    public StreamingChatModel streamingChatModel(WeFlowLlmProperties properties) {
        WeFlowLlmProperties.ProviderProperties providerProperties = properties.defaultProviderProperties();
        providerProperties.validateOpenAiCompatible(properties.defaultProvider());

        return OpenAiStreamingChatModel.builder()
                .baseUrl(providerProperties.baseUrl())
                .apiKey(providerProperties.apiKey())
                .modelName(providerProperties.modelName())
                .temperature(providerProperties.temperature())
                .build();
    }
}
