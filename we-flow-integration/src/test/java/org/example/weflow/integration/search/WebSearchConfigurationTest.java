package org.example.weflow.integration.search;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class WebSearchConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(WebSearchConfiguration.class);

    @Test
    void shouldNotCreateSearchClientWhenDisabledByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(WebSearchProperties.class);
            assertThat(context).doesNotHaveBean(WebSearchClient.class);
            assertThat(context.getBean(WebSearchProperties.class).isEnabled()).isFalse();
            assertThat(context.getBean(WebSearchProperties.class).provider()).isEqualTo("jina");
        });
    }

    @Test
    void shouldCreateDuckDuckGoSearchClientWhenEnabled() {
        contextRunner
                .withPropertyValues(
                        "we-flow.search.enabled=true",
                        "we-flow.search.provider=duckduckgo"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(WebSearchClient.class);
                    assertThat(context.getBean(WebSearchClient.class)).isInstanceOf(DuckDuckGoWebSearchClient.class);
                    assertThat(context.getBean(WebSearchProperties.class).provider()).isEqualTo("duckduckgo");
                    assertThat(context.getBean(WebSearchProperties.class).proxy().isEnabled()).isFalse();
                });
    }

    @Test
    void shouldCreateJinaSearchClientWhenEnabledByDefaultProvider() {
        contextRunner
                .withPropertyValues(
                        "we-flow.search.enabled=true",
                        "we-flow.search.jina.api-key=test-key"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(WebSearchClient.class);
                    assertThat(context.getBean(WebSearchClient.class)).isInstanceOf(JinaWebSearchClient.class);
                    assertThat(context.getBean(WebSearchProperties.class).provider()).isEqualTo("jina");
                });
    }

    @Test
    void shouldCreateJinaSearchClientWhenProviderIsJina() {
        contextRunner
                .withPropertyValues(
                        "we-flow.search.enabled=true",
                        "we-flow.search.provider=jina",
                        "we-flow.search.jina.api-key=test-key",
                        "we-flow.search.jina.base-url=https://example.com/search",
                        "we-flow.search.jina.no-cache=true",
                        "we-flow.search.jina.max-snippet-chars=120",
                        "we-flow.search.jina.max-response-bytes=1048576",
                        "we-flow.search.jina.max-tokens=2000"
                )
                .run(context -> {
                    WebSearchProperties.JinaProperties jina = context.getBean(WebSearchProperties.class).jina();

                    assertThat(context).hasSingleBean(WebSearchClient.class);
                    assertThat(context.getBean(WebSearchClient.class)).isInstanceOf(JinaWebSearchClient.class);
                    assertThat(jina.apiKey()).isEqualTo("test-key");
                    assertThat(jina.baseUrl()).isEqualTo("https://example.com/search");
                    assertThat(jina.isNoCache()).isTrue();
                    assertThat(jina.maxSnippetChars()).isEqualTo(120);
                    assertThat(jina.maxResponseBytes()).isEqualTo(1048576);
                    assertThat(jina.maxTokens()).isEqualTo(2000);
                });
    }

    @Test
    void shouldFailWhenJinaProviderHasBlankApiKey() {
        contextRunner
                .withPropertyValues(
                        "we-flow.search.enabled=true",
                        "we-flow.search.provider=jina"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage("Missing property: we-flow.search.jina.api-key");
                });
    }

    @Test
    void shouldCreateSearchClientWhenProxyIsEnabled() {
        contextRunner
                .withPropertyValues(
                        "we-flow.search.enabled=true",
                        "we-flow.search.provider=duckduckgo",
                        "we-flow.search.proxy.enabled=true",
                        "we-flow.search.proxy.host=127.0.0.1",
                        "we-flow.search.proxy.port=7890"
                )
                .run(context -> {
                    WebSearchProperties.ProxyProperties proxy = context.getBean(WebSearchProperties.class).proxy();

                    assertThat(context).hasSingleBean(WebSearchClient.class);
                    assertThat(proxy.isEnabled()).isTrue();
                    assertThat(proxy.host()).isEqualTo("127.0.0.1");
                    assertThat(proxy.port()).isEqualTo(7890);
                });
    }

    @Test
    void shouldCreateSearchClientWhenProxyCredentialsAreBlank() {
        contextRunner
                .withPropertyValues(
                        "we-flow.search.enabled=true",
                        "we-flow.search.provider=duckduckgo",
                        "we-flow.search.proxy.enabled=true",
                        "we-flow.search.proxy.host=127.0.0.1",
                        "we-flow.search.proxy.port=7890",
                        "we-flow.search.proxy.username=",
                        "we-flow.search.proxy.password="
                )
                .run(context -> {
                    WebSearchProperties.ProxyProperties proxy = context.getBean(WebSearchProperties.class).proxy();

                    assertThat(context).hasSingleBean(WebSearchClient.class);
                    assertThat(proxy.username()).isEmpty();
                    assertThat(proxy.password()).isEmpty();
                });
    }
}
