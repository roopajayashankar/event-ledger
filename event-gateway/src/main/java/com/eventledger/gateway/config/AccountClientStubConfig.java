package com.eventledger.gateway.config;

import com.eventledger.gateway.service.AccountClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Temporary stub for the apply step until the real Account Service client is
 * wired in. It always "succeeds" with no downstream call, so local event
 * handling works in isolation.
 *
 * <p>{@link ConditionalOnMissingBean} means defining a real {@link AccountClient}
 * bean (the RestClient + resiliency implementation) automatically takes over —
 * no caller changes, no bean conflict.
 */
@Configuration
public class AccountClientStubConfig {

    private static final Logger log = LoggerFactory.getLogger(AccountClientStubConfig.class);

    @Bean
    @ConditionalOnMissingBean(AccountClient.class)
    public AccountClient stubAccountClient() {
        return command -> log.debug(
                "Stub apply (no downstream call): account={} eventId={} {} {} {}",
                command.accountId(), command.eventId(), command.type(),
                command.amount(), command.currency());
    }
}
