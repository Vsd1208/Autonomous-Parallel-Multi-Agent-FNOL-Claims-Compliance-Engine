package com.guidewire.fnol.api;

import com.guidewire.fnol.api.persistence.ClaimEntity;
import com.guidewire.fnol.api.persistence.ClaimRepository;
import com.guidewire.fnol.common.Models.Claim;
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

import java.math.BigDecimal;
import java.time.Instant;
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
    /** Work queue of a claim filed in the claims system whose analysis has not finished. */
    public static final String ANALYSIS_PENDING = "ANALYSIS_PENDING";

    private final boolean enabled;
    private final ClaimCenterClient claimCenter;
    private final InputGuardrail guardrail;
    private final DuplicateSubmissionRecorder duplicates;
    private final ClaimRepository claims;

    ClaimsSystemIntake(@Value("${fnol.claims-events.secret:}") String eventSecret, ClaimCenterClient claimCenter,
                       InputGuardrail guardrail, DuplicateSubmissionRecorder duplicates, ClaimRepository claims) {
        this.enabled = eventSecret != null && !eventSecret.isBlank();
        this.claimCenter = claimCenter;
        this.guardrail = guardrail;
        this.duplicates = duplicates;
        this.claims = claims;
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
     * An unsaved record of a claim the claims system holds but the engine has not analysed, for showing: status
     * ANALYSIS_PENDING while it waits, otherwise its queue, with the reason (the analysis could not start, so a person
     * decides without the AI). Never saved here: a finished analysis about to be saved must not be overwritten. Empty
     * if there is no such claim, or the claims system cannot be reached.
     */
    Optional<ClaimEntity> recordOf(String claimId) {
        if (!enabled) {
            return Optional.empty();
        }
        Claim filed;
        try {
            filed = claimCenter.getClaim(claimId);
        } catch (RuntimeException e) {
            log.warn("[Intake] Claim {} could not be read from the claims system: {}", claimId, e.getMessage());
            return Optional.empty();
        }
        if (filed == null) {
            return Optional.empty();
        }
        ClaimEntity record = new ClaimEntity();
        record.claimId = filed.claimId();
        record.policyNumber = filed.policyNumber();
        record.incidentDate = filed.incidentDate();
        record.state = filed.state();
        record.estimatedDamage = BigDecimal.ZERO;
        record.reserve = BigDecimal.ZERO;
        record.ccSyncStatus = "FILED";
        record.createdAt = Instant.now();
        record.updatedAt = record.createdAt;
        if (filed.workQueue() == null || ANALYSIS_PENDING.equals(filed.workQueue())) {
            record.status = ANALYSIS_PENDING;
            return Optional.of(record);
        }
        record.status = filed.workQueue();
        record.failureReason = "The AI analysis did not complete; this claim is handled without it";
        return Optional.of(record);
    }

    /** Saves a record from {@link #recordOf} because a decision needs one, unless the analysis has saved one since. */
    ClaimEntity saveForDecision(ClaimEntity record) {
        return claims.findById(record.claimId).orElseGet(() -> claims.save(record));
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
