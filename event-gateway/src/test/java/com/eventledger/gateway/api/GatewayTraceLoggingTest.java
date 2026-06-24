package com.eventledger.gateway.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.eventledger.gateway.service.AccountClient;
import com.eventledger.gateway.service.EventService;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Confirms trace context reaches the logs: a request handled inside an active
 * trace produces a Gateway log line whose MDC carries the {@code traceId}, so
 * logs correlate to traces (CLAUDE.md requirement 8 / observability).
 */
@SpringBootTest
@AutoConfigureMockMvc
class GatewayTraceLoggingTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObservationRegistry observationRegistry;

    @MockitoBean
    private AccountClient accountClient;

    @Test
    void gatewayLogsCarryTraceIdInMdc() throws Exception {
        Logger eventServiceLogger = (Logger) LoggerFactory.getLogger(EventService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        eventServiceLogger.addAppender(appender);
        try {
            // Handle the request inside an active trace, as a real inbound request
            // would be; the "Stored event" log then runs with a current span.
            Observation inbound = Observation.start("test.inbound", observationRegistry);
            try (Observation.Scope scope = inbound.openScope()) {
                mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"eventId":"evt-trace-log","accountId":"acc-tl","type":"CREDIT","amount":10.00,"currency":"USD","eventTimestamp":"2026-06-01T10:00:00Z"}
                                        """))
                        .andExpect(status().isCreated());
            } finally {
                inbound.stop();
            }
        } finally {
            eventServiceLogger.detachAppender(appender);
        }

        assertThat(appender.list).anySatisfy(logEvent ->
                assertThat(logEvent.getMDCPropertyMap().get("traceId")).matches("[0-9a-f]{32}"));
    }
}
