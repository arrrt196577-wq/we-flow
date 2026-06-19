package org.example.weflow.integration.fetch;

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
@EnableConfigurationProperties(WebFetchProperties.class)
public class WebFetchConfiguration {

    @Bean
    @ConditionalOnMissingBean(WebFetchClient.class)
    @ConditionalOnProperty(prefix = "we-flow.fetch", name = "enabled", havingValue = "true")
    @ConditionalOnProperty(prefix = "we-flow.fetch", name = "provider", havingValue = "jina", matchIfMissing = true)
    public WebFetchClient jinaWebFetchClient(WebFetchProperties properties) {
        if (!StringUtils.hasText(properties.jina().apiKey())) {
            throw new IllegalStateException("Missing property: we-flow.fetch.jina.api-key");
        }
        return new JinaWebFetchClient(webFetchWebClientBuilder(properties), properties);
    }

    private WebClient.Builder webFetchWebClientBuilder(WebFetchProperties properties) {
        HttpClient httpClient = HttpClient.create();
        WebFetchProperties.ProxyProperties proxy = properties.proxy();
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
