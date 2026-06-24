package com.eventledger.gateway.config;

import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.TextMapPropagator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Explicitly registers the W3C trace-context propagator. Spring Boot composes
 * all {@link TextMapPropagator} beans into the OpenTelemetry context
 * propagators; registering this guarantees the {@code traceparent} header is
 * injected on outbound calls and extracted on inbound ones, so one request is a
 * single trace across the Gateway and Account Service (CLAUDE.md).
 */
@Configuration
public class TracingConfig {

    @Bean
    TextMapPropagator w3cTraceContextPropagator() {
        return W3CTraceContextPropagator.getInstance();
    }
}
