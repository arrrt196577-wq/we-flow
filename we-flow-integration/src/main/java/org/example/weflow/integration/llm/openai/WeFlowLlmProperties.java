package org.example.weflow.integration.llm.openai;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "we-flow.llm")
public record WeFlowLlmProperties(
        String defaultProvider,
        Map<String, ProviderProperties> providers
) {

    ProviderProperties defaultProviderProperties() {
        if (!hasText(defaultProvider)) {
            throw new IllegalStateException("Missing property: we-flow.llm.default-provider");
        }
        if (providers == null || providers.isEmpty()) {
            throw new IllegalStateException("Missing property: we-flow.llm.providers");
        }

        ProviderProperties providerProperties = providers.get(defaultProvider);
        if (providerProperties == null) {
            throw new IllegalStateException("Missing provider config: we-flow.llm.providers." + defaultProvider);
        }
        return providerProperties;
    }

    public record ProviderProperties(
            String type,
            String baseUrl,
            String apiKey,
            String modelName,
            Double temperature
    ) {

        void validateOpenAiCompatible(String providerName) {
            if (hasText(type) && !"openai-compatible".equals(type)) {
                throw new IllegalStateException("Provider " + providerName + " is not openai-compatible.");
            }
            if (!hasText(baseUrl)) {
                throw new IllegalStateException("Missing property: we-flow.llm.providers." + providerName + ".base-url");
            }
            if (!hasText(apiKey)) {
                throw new IllegalStateException("Missing property: we-flow.llm.providers." + providerName + ".api-key");
            }
            if (!hasText(modelName)) {
                throw new IllegalStateException("Missing property: we-flow.llm.providers." + providerName + ".model-name");
            }
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
