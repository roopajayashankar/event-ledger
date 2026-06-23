package com.eventledger.gateway.api;

import com.eventledger.gateway.api.dto.EventRequest;
import com.eventledger.gateway.api.dto.EventResponse;
import com.eventledger.gateway.service.EventService;
import com.eventledger.gateway.service.EventService.SubmitResult;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public event API. Thin: validation is declarative on the DTO and all
 * idempotency / persistence logic lives in {@link EventService}. Reads are
 * served entirely from the local store, so they work regardless of Account
 * Service health (graceful degradation, requirement 6).
 */
@RestController
@RequestMapping("/events")
public class EventController {

    private final EventService eventService;

    public EventController(EventService eventService) {
        this.eventService = eventService;
    }

    /**
     * Submits an event. {@code 201 Created} when newly applied and stored,
     * {@code 200 OK} for a duplicate {@code eventId} (returns the stored event,
     * does no work).
     */
    @PostMapping
    public ResponseEntity<EventResponse> submit(@Valid @RequestBody EventRequest request) {
        SubmitResult result = eventService.submit(request);
        EventResponse body = EventResponse.from(result.event());
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(body);
    }

    @GetMapping("/{eventId}")
    public EventResponse getEvent(@PathVariable String eventId) {
        return eventService.getEvent(eventId);
    }

    @GetMapping
    public List<EventResponse> getEventsByAccount(@RequestParam("account") String accountId) {
        return eventService.getEventsByAccount(accountId);
    }
}
