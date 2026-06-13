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
        });
    }

    @Test
    void shouldCreateDuckDuckGoSearchClientWhenEnabled() {
        contextRunner
                .withPropertyValues("we-flow.search.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(WebSearchClient.class);
                    assertThat(context.getBean(WebSearchProperties.class).provider()).isEqualTo("duckduckgo");
                    assertThat(context.getBean(WebSearchProperties.class).proxy().isEnabled()).isFalse();
                });
    }

    @Test
    void shouldCreateSearchClientWhenProxyIsEnabled() {
        contextRunner
                .withPropertyValues(
                        "we-flow.search.enabled=true",
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
