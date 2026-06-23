package com.eventledger.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Event Gateway (public, :8080). Entry point for clients: validates input,
 * enforces idempotency at the API boundary, owns the event log, and proxies to
 * the internal Account Service under a resiliency wrapper.
 */
@SpringBootApplication
public class EventGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(EventGatewayApplication.class, args);
    }
}
