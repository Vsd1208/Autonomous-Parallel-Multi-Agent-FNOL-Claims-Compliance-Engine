package com.guidewire.fnol.common;

/** The policy was not found, is not active, or has no coverage that matches the loss. */
public class CoverageNotEligibleException extends RuntimeException {
    public CoverageNotEligibleException(String message) {
        super(message);
    }
}
