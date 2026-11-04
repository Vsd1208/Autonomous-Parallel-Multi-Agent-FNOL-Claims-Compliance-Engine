package com.guidewire.fnol.enrichment;

import org.springframework.web.client.ResourceAccessException;

/**
 * A Guidewire call that could not be answered now. A resource-access failure, so the event inbox retries it and then dead-letters it,
 * and the claim reaches a person. Its message names the reason and status only: never a token, a secret or a response body.
 */
public class GuidewireUnavailableException extends ResourceAccessException {

    public enum Reason { AUTHENTICATION, RATE_LIMITED, SERVER_ERROR, UNREACHABLE }

    private final Reason reason;

    public GuidewireUnavailableException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
