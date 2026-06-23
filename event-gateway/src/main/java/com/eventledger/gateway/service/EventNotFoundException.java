package com.eventledger.gateway.service;

/** Raised when a local event read targets an unknown {@code eventId}. */
public class EventNotFoundException extends RuntimeException {

    public EventNotFoundException(String eventId) {
        super("Event not found: " + eventId);
    }
}
