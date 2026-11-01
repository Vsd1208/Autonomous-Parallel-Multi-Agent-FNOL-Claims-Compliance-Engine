package com.guidewire.fnol.api;

import com.guidewire.fnol.common.Models.ClaimCreateResponse;
import com.guidewire.fnol.common.Models.FNOLPayload;
import com.guidewire.fnol.enrichment.ClaimCenterClient;
import com.guidewire.fnol.enrichment.DuplicateLossException;
import com.guidewire.fnol.guardrails.InputGuardrail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.util.Optional;

/**
 * Reports a loss to the claims system, which owns the claim. The claims system creates the claim, checks for a
 * duplicate and announces the submission; the AI analysis follows from that event ({@link ClaimEventController}) and
 * is written back onto the claim. The caller gets the claim number at once.
 *
 * <p>Active when the claims system's events are configured (a signing secret is set). Without one the claims system
 * could not tell the engine about the claim, so callers fall back to the direct intake, which files the claim itself.
 */
@Component
public class ClaimsSystemIntake {

    private static final Logger log = LoggerFactory.getLogger(ClaimsSystemIntake.class);
    public static final String CLAIMS_SYSTEM = "CLAIMS_SYSTEM";
    public static final String DIRECT = "DIRECT";

    private final boolean enabled;
    private final ClaimCenterClient claimCenter;
    private final InputGuardrail guardrail;
    private final DuplicateSubmissionRecorder duplicates;

    ClaimsSystemIntake(@Value("${fnol.claims-events.secret:}") String eventSecret, ClaimCenterClient claimCenter,
                       InputGuardrail guardrail, DuplicateSubmissionRecorder duplicates) {
        this.enabled = eventSecret != null && !eventSecret.isBlank();
        this.claimCenter = claimCenter;
        this.guardrail = guardrail;
        this.duplicates = duplicates;
        log.info("[Intake] New losses are reported {}", enabled
                ? "to the claims system; the AI analysis follows its claim.submitted event"
                : "through the direct intake (no claims-system event secret is configured)");
    }

    public boolean enabled() {
        return enabled;
    }

    /** CLAIMS_SYSTEM or DIRECT, for the health endpoint. */
    public String mode() {
        return enabled ? CLAIMS_SYSTEM : DIRECT;
    }

    /**
     * Files the loss. Input the claimant must correct is refused before anything is filed (400); a loss already
     * reported is refused by the claims system (409), and the original claim is flagged here as well.
     *
     * @return the claims system's claim number and status (SUBMITTED)
     */
    public ClaimCreateResponse report(FNOLPayload fnol) {
        guardrail.validate(fnol);
        try {
            ClaimCreateResponse filed = claimCenter.submitFnol(fnol);
            log.info("[Intake] Loss reported to the claims system as {} (channel {})", filed.claimId(), fnol.channel());
            return filed;
        } catch (DuplicateLossException e) {
            Optional<String> flagged = duplicates.record(fnol);
            String original = e.originalClaimId() != null ? e.originalClaimId() : flagged.orElse(null);
            throw new DuplicateFNOLException(original == null ? "This loss has already been reported"
                    : "This FNOL has already been submitted as " + original
                            + "; the original claim is flagged for adjuster verification");
        } catch (ResourceAccessException | HttpServerErrorException e) {
            log.error("[Intake] The claims system could not be reached; nothing was filed: {}", e.getMessage());
            throw new ClaimsSystemUnavailableException();
        }
    }
}

class ClaimsSystemUnavailableException extends RuntimeException {
    ClaimsSystemUnavailableException() {
        super("The claims system could not be reached, so the claim was not filed. Please try again shortly.");
    }
}
