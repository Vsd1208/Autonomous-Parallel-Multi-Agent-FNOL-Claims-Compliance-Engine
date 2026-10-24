package com.guidewire.fnol.api;

import com.guidewire.fnol.agents.AuditTrailAgent;
import com.guidewire.fnol.agents.PiiRedactionAgent;
import com.guidewire.fnol.api.persistence.ClaimEntity;
import com.guidewire.fnol.api.persistence.ClaimRepository;
import com.guidewire.fnol.common.Models.FNOLPayload;
import com.guidewire.fnol.common.Models.SanitizedIntake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Duplicate FNOL check, "require human verification": the duplicate is refused (409), and the
 * original claim gets a DUPLICATE_SUBMISSION flag and an audit event so an adjuster verifies it.
 * A claim that was filed straight through with no human step goes back to HUMAN_REVIEW.
 */
@Component
public class DuplicateSubmissionRecorder {

    static final String DUPLICATE_SUBMISSION = "DUPLICATE_SUBMISSION";

    private static final Logger log = LoggerFactory.getLogger(DuplicateSubmissionRecorder.class);

    private final ClaimRepository claims;
    private final AuditService auditService;
    private final AuditTrailAgent auditAgent;
    private final PiiRedactionAgent redaction;

    public DuplicateSubmissionRecorder(ClaimRepository claims, AuditService auditService,
                                       AuditTrailAgent auditAgent, PiiRedactionAgent redaction) {
        this.claims = claims;
        this.auditService = auditService;
        this.auditAgent = auditAgent;
        this.redaction = redaction;
    }

    /**
     * @return the original claim's ID, or empty when the original is still being processed
     *         (in-flight duplicate) or the record could not be updated.
     */
    public Optional<String> record(FNOLPayload payload) {
        try {
            List<ClaimEntity> originals = claims.findDuplicatesOf(
                    payload.policyNumber(), payload.incidentDate(), payload.state());
            if (originals.isEmpty()) {
                return Optional.empty();
            }
            ClaimEntity original = originals.get(0);

            Set<String> flags = new LinkedHashSet<>();
            if (original.gateFlags != null && !original.gateFlags.isBlank()) {
                flags.addAll(List.of(original.gateFlags.split(",")));
            }
            flags.add(DUPLICATE_SUBMISSION);
            original.gateFlags = String.join(",", flags);
            String previousStatus = original.status;
            if ("STRAIGHT_THROUGH_PROCESSING".equals(original.status)) {
                original.status = "HUMAN_REVIEW";
            }
            original.updatedAt = Instant.now();
            claims.save(original);

            SanitizedIntake sanitized = SanitizedIntake.of(payload, redaction.execute(payload).redactedDescription());
            auditService.append(original.claimId, List.of(auditAgent.record(original.claimId, "DuplicateCheck",
                    sanitized, "previousStatus=" + previousStatus + ";status=" + original.status
                            + ";flag=" + DUPLICATE_SUBMISSION, "DUPLICATE_FNOL")), AuditService.SYSTEM_ACTOR);

            log.warn("[FNOL] Duplicate FNOL for policy={} refused; original claim {} flagged for human verification",
                    payload.policyNumber(), original.claimId);
            return Optional.of(original.claimId);
        } catch (RuntimeException e) {
            log.error("[FNOL] Could not record duplicate submission — policy={}", payload.policyNumber(), e);
            return Optional.empty();
        }
    }
}
