package com.eventledger.account.api;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Custom liveness/readiness endpoint, separate from {@code /actuator/health}.
 *
 * <p>Returns {@code {status, service, db, timestamp}} and actively probes H2
 * connectivity rather than returning 200 blindly — if the DB check fails the
 * body reports {@code DOWN} and the response is 503. See CLAUDE.md / the
 * observability-conventions skill.
 */
@RestController
public class HealthController {

    private static final String SERVICE_NAME = "account-service";

    private final DataSource dataSource;

    public HealthController(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        boolean dbUp = isDatabaseReachable();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", dbUp ? "UP" : "DOWN");
        body.put("service", SERVICE_NAME);
        body.put("db", dbUp ? "UP" : "DOWN");
        body.put("timestamp", Instant.now().toString());

        return ResponseEntity
                .status(dbUp ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .body(body);
    }

    /**
     * Actively validates the connection (JDBC {@code isValid}) so a broken
     * datasource surfaces as DOWN instead of a misleading UP.
     */
    private boolean isDatabaseReachable() {
        try (var connection = dataSource.getConnection()) {
            return connection.isValid(1);
        } catch (Exception e) {
            return false;
        }
    }
}
