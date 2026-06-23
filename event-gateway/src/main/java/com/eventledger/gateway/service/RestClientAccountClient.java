package com.eventledger.gateway.service;

import com.eventledger.gateway.api.dto.BalanceResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Calls Account Service over synchronous REST. Failure classification drives the
 * Gateway's consistency and degradation behaviour:
 *
 * <ul>
 *   <li>connectivity / timeout / 5xx → {@link AccountUnavailableException} (503,
 *       event not persisted — client retries)</li>
 *   <li>4xx → {@link AccountRejectedException} (502, not retried, not persisted)</li>
 *   <li>404 on balance → {@link AccountNotFoundException} (404)</li>
 * </ul>
 */
@Component
public class RestClientAccountClient implements AccountClient {

    private static final Logger log = LoggerFactory.getLogger(RestClientAccountClient.class);

    private final RestClient restClient;

    public RestClientAccountClient(RestClient accountRestClient) {
        this.restClient = accountRestClient;
    }

    @Override
    public void apply(ApplyTransactionCommand command) {
        AccountTransactionRequest payload = new AccountTransactionRequest(
                command.eventId(), command.type().name(), command.amount(), command.currency());
        try {
            restClient.post()
                    .uri("/accounts/{accountId}/transactions", command.accountId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException ex) {
            log.warn("Account rejected transaction eventId={} account={}: {}",
                    command.eventId(), command.accountId(), ex.getStatusCode());
            throw new AccountRejectedException(ex.getStatusCode().value(), ex);
        } catch (RestClientException ex) {
            log.warn("Account Service unavailable applying eventId={} account={}: {}",
                    command.eventId(), command.accountId(), ex.getMessage());
            throw new AccountUnavailableException(ex);
        }
    }

    @Override
    public BalanceResponse getBalance(String accountId) {
        try {
            return restClient.get()
                    .uri("/accounts/{accountId}/balance", accountId)
                    .retrieve()
                    .body(BalanceResponse.class);
        } catch (HttpClientErrorException.NotFound ex) {
            throw new AccountNotFoundException(accountId);
        } catch (RestClientException ex) {
            log.warn("Account Service unavailable reading balance account={}: {}", accountId, ex.getMessage());
            throw new AccountUnavailableException(ex);
        }
    }
}
