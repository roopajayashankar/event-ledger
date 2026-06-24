package com.eventledger.gateway.config;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Builds the RestClient used to call Account Service.
 *
 * <p>It is built from the <em>autoconfigured</em> {@link RestClient.Builder}, not
 * a hand-rolled instance, so Spring's instrumentation is retained — the W3C
 * {@code traceparent} header will propagate automatically once Micrometer
 * Tracing is wired, giving one trace spanning both services (CLAUDE.md /
 * observability-conventions). We only add the base URL and connect/read
 * timeouts on top.
 */
@Configuration
@EnableConfigurationProperties(AccountServiceProperties.class)
public class AccountClientConfig {

    @Bean
    public RestClient accountRestClient(
            RestClient.Builder builder,
            AccountServiceProperties properties,
            ObservationRegistry observationRegistry) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(properties.connectTimeout())
                .withReadTimeout(properties.readTimeout());
        // Use a plain HTTP/1.1 factory rather than detect() (which may select the
        // JDK HttpClient and negotiate HTTP/2). One small internal JSON call
        // doesn't need HTTP/2, and HTTP/1.1 avoids RST_STREAM negotiation issues.
        //
        // Wiring the ObservationRegistry makes each call a client observation, so
        // the W3C traceparent header is injected and the trace spans both services.
        return builder
                .baseUrl(properties.baseUrl())
                .requestFactory(ClientHttpRequestFactoryBuilder.simple().build(settings))
                .observationRegistry(observationRegistry)
                .build();
    }
}
