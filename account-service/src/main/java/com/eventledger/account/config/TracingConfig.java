package com.eventledger.account.config;

import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.TextMapPropagator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Explicitly registers the W3C trace-context propagator. Spring Boot composes
 * all {@link TextMapPropagator} beans into the OpenTelemetry context
 * propagators; registering this lets Account <em>extract</em> the inbound
 * {@code traceparent} header from the Gateway and continue the same trace, so a
 * single client request is one trace across both services (CLAUDE.md).
 */
@Configuration
public class TracingConfig {

    @Bean
    TextMapPropagator w3cTraceContextPropagator() {
        return W3CTraceContextPropagator.getInstance();
    }
}
