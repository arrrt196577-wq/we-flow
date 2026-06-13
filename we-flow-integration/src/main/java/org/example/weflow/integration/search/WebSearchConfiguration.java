package org.example.weflow.integration.search;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.transport.ProxyProvider;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WebSearchProperties.class)
public class WebSearchConfiguration {

    @Bean
    @ConditionalOnMissingBean(WebSearchClient.class)
    @ConditionalOnProperty(prefix = "we-flow.search", name = "enabled", havingValue = "true")
    @ConditionalOnProperty(prefix = "we-flow.search", name = "provider", havingValue = "duckduckgo", matchIfMissing = true)
    public WebSearchClient duckDuckGoWebSearchClient(WebSearchProperties properties) {
        return new DuckDuckGoWebSearchClient(webSearchWebClientBuilder(properties), properties);
    }

    private WebClient.Builder webSearchWebClientBuilder(WebSearchProperties properties) {
        HttpClient httpClient = HttpClient.create();
        WebSearchProperties.ProxyProperties proxy = properties.proxy();
        if (proxy.isEnabled()) {
            httpClient = httpClient.proxy(proxySpec -> {
                ProxyProvider.Builder proxyBuilder = proxySpec.type(ProxyProvider.Proxy.HTTP)
                        .host(proxy.host())
                        .port(proxy.port());
                if (StringUtils.hasText(proxy.username())) {
                    proxyBuilder.username(proxy.username())
                            .password(password -> proxy.password());
                }
            });
        }
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient));
    }
}
