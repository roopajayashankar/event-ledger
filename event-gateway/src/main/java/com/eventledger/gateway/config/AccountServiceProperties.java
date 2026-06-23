package com.eventledger.gateway.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for reaching Account Service. {@code baseUrl} is overridable via
 * {@code application.yml} or the {@code ACCOUNT_SERVICE_BASE_URL} environment
 * variable (Spring relaxed binding) so docker-compose and tests can point the
 * Gateway at the right host.
 *
 * <p>Timeouts live on the HTTP client itself (CLAUDE.md): they bound latency so
 * a slow Account Service can't tie up Gateway request threads.
 */
@ConfigurationProperties(prefix = "account-service")
public record AccountServiceProperties(String baseUrl, Duration connectTimeout, Duration readTimeout) {

    public AccountServiceProperties {
        if (baseUrl == null) {
            baseUrl = "http://localhost:8081";
        }
        if (connectTimeout == null) {
            connectTimeout = Duration.ofSeconds(1);
        }
        if (readTimeout == null) {
            readTimeout = Duration.ofSeconds(2);
        }
    }
}
