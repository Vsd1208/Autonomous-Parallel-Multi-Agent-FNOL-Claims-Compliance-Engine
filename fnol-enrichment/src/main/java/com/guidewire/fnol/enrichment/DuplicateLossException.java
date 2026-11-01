package com.guidewire.fnol.enrichment;

/** The claims system refused a report because the same loss was already reported, as {@link #originalClaimId()}. */
public class DuplicateLossException extends RuntimeException {

    private final String originalClaimId;

    public DuplicateLossException(String originalClaimId) {
        super("This loss has already been reported" + (originalClaimId == null ? "" : " as " + originalClaimId));
        this.originalClaimId = originalClaimId;
    }

    public String originalClaimId() {
        return originalClaimId;
    }
}
