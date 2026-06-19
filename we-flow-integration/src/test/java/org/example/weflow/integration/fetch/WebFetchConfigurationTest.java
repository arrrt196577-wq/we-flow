package org.example.weflow.integration.fetch;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class WebFetchConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(WebFetchConfiguration.class);

    @Test
    void shouldNotCreateFetchClientWhenDisabledByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(WebFetchProperties.class);
            assertThat(context).doesNotHaveBean(WebFetchClient.class);
            assertThat(context.getBean(WebFetchProperties.class).isEnabled()).isFalse();
            assertThat(context.getBean(WebFetchProperties.class).provider()).isEqualTo("jina");
        });
    }

    @Test
    void shouldCreateJinaFetchClientWhenEnabledByDefaultProvider() {
        contextRunner
                .withPropertyValues(
                        "we-flow.fetch.enabled=true",
                        "we-flow.fetch.jina.api-key=test-key"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(WebFetchClient.class);
                    assertThat(context.getBean(WebFetchClient.class)).isInstanceOf(JinaWebFetchClient.class);
                    assertThat(context.getBean(WebFetchProperties.class).provider()).isEqualTo("jina");
                });
    }

    @Test
    void shouldCreateJinaFetchClientWhenProviderIsJina() {
        contextRunner
                .withPropertyValues(
                        "we-flow.fetch.enabled=true",
                        "we-flow.fetch.provider=jina",
                        "we-flow.fetch.max-content-chars=9000",
                        "we-flow.fetch.jina.api-key=test-key",
                        "we-flow.fetch.jina.base-url=https://example.com/fetch",
                        "we-flow.fetch.jina.no-cache=true",
                        "we-flow.fetch.jina.max-response-bytes=1048576",
                        "we-flow.fetch.jina.max-tokens=2000"
                )
                .run(context -> {
                    WebFetchProperties properties = context.getBean(WebFetchProperties.class);
                    WebFetchProperties.JinaProperties jina = properties.jina();

                    assertThat(context).hasSingleBean(WebFetchClient.class);
                    assertThat(context.getBean(WebFetchClient.class)).isInstanceOf(JinaWebFetchClient.class);
                    assertThat(properties.maxContentChars()).isEqualTo(9000);
                    assertThat(jina.apiKey()).isEqualTo("test-key");
                    assertThat(jina.baseUrl()).isEqualTo("https://example.com/fetch");
                    assertThat(jina.isNoCache()).isTrue();
                    assertThat(jina.maxResponseBytes()).isEqualTo(1048576);
                    assertThat(jina.maxTokens()).isEqualTo(2000);
                });
    }

    @Test
    void shouldFailWhenJinaProviderHasBlankApiKey() {
        contextRunner
                .withPropertyValues(
                        "we-flow.fetch.enabled=true",
                        "we-flow.fetch.provider=jina"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage("Missing property: we-flow.fetch.jina.api-key");
                });
    }

    @Test
    void shouldCreateFetchClientWhenProxyIsEnabled() {
        contextRunner
                .withPropertyValues(
                        "we-flow.fetch.enabled=true",
                        "we-flow.fetch.jina.api-key=test-key",
                        "we-flow.fetch.proxy.enabled=true",
                        "we-flow.fetch.proxy.host=127.0.0.1",
                        "we-flow.fetch.proxy.port=7890"
                )
                .run(context -> {
                    WebFetchProperties.ProxyProperties proxy = context.getBean(WebFetchProperties.class).proxy();

                    assertThat(context).hasSingleBean(WebFetchClient.class);
                    assertThat(proxy.isEnabled()).isTrue();
                    assertThat(proxy.host()).isEqualTo("127.0.0.1");
                    assertThat(proxy.port()).isEqualTo(7890);
                });
    }

    @Test
    void shouldCreateFetchClientWhenProxyCredentialsAreBlank() {
        contextRunner
                .withPropertyValues(
                        "we-flow.fetch.enabled=true",
                        "we-flow.fetch.jina.api-key=test-key",
                        "we-flow.fetch.proxy.enabled=true",
                        "we-flow.fetch.proxy.host=127.0.0.1",
                        "we-flow.fetch.proxy.port=7890",
                        "we-flow.fetch.proxy.username=",
                        "we-flow.fetch.proxy.password="
                )
                .run(context -> {
                    WebFetchProperties.ProxyProperties proxy = context.getBean(WebFetchProperties.class).proxy();

                    assertThat(context).hasSingleBean(WebFetchClient.class);
                    assertThat(proxy.username()).isEmpty();
                    assertThat(proxy.password()).isEmpty();
                });
    }
}
