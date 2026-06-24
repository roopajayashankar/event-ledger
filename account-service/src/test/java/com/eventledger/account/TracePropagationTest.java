package com.eventledger.account;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.propagation.Propagator;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Confirms Account continues the Gateway's trace: extracting an inbound W3C
 * {@code traceparent} yields a span with the <em>same</em> trace id, so one
 * client request is a single trace spanning both services.
 */
@SpringBootTest
class TracePropagationTest {

    @Autowired
    private Propagator propagator;

    private Span span;

    @AfterEach
    void cleanUp() {
        if (span != null) {
            span.end();
        }
    }

    @Test
    void extractsInboundTraceparentIntoTheSameTrace() {
        String traceId = "0af7651916cd43dd8448eb211c80319c";
        String traceparent = "00-" + traceId + "-b7ad6b7169203331-01";
        Map<String, String> headers = Map.of("traceparent", traceparent);

        span = propagator.extract(headers, Map::get).start();

        assertThat(span.context().traceId()).isEqualTo(traceId);
    }
}
