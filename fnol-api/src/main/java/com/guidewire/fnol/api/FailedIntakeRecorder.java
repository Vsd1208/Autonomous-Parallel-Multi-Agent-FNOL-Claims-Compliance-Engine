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

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

/**
 * Saves an intake that failed before ClaimCenter filed it (compliance block, uncovered policy,
 * timeout) as a REJ-... claim with an audit event, so no claim disappears and an adjuster can
 * pick it up from the queue. Only the sanitized intake is hashed; the description and PII are
 * never stored.
 */
@Component
public class FailedIntakeRecorder {

    private static final Logger log = LoggerFactory.getLogger(FailedIntakeRecorder.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ClaimRepository claims;
    private final AuditService auditService;
    private final AuditTrailAgent auditAgent;
    private final PiiRedactionAgent redaction;

    public FailedIntakeRecorder(ClaimRepository claims, AuditService auditService,
                                AuditTrailAgent auditAgent, PiiRedactionAgent redaction) {
        this.claims = claims;
        this.auditService = auditService;
        this.auditAgent = auditAgent;
        this.redaction = redaction;
    }

    /**
     * @return the REJ-... claim ID, or null if the record itself could not be saved
     *         (the original error is still returned to the caller).
     */
    public String record(FNOLPayload payload, String status, String errorCode, String reason) {
        try {
            String claimId = newClaimId();
            ClaimEntity e = new ClaimEntity();
            e.claimId = claimId;
            e.policyNumber = payload.policyNumber();
            e.status = status;
            e.incidentDate = payload.incidentDate();
            e.state = payload.state();
            e.estimatedDamage = BigDecimal.ZERO;
            e.reserve = BigDecimal.ZERO;
            e.channel = payload.channel();
            e.failureReason = truncate(reason, 500);
            e.ccSyncStatus = "NOT_FILED";
            e.createdAt = Instant.now();
            e.updatedAt = e.createdAt;
            claims.save(e);

            SanitizedIntake sanitized = SanitizedIntake.of(payload, redaction.execute(payload).redactedDescription());
            auditService.append(claimId,
                    List.of(auditAgent.record(claimId, "Intake", sanitized, reason, errorCode)),
                    AuditService.SYSTEM_ACTOR);

            log.warn("[FNOL] Intake failed ({}); saved as {} with status {} for human attention — policy={}",
                    errorCode, claimId, status, payload.policyNumber());
            return claimId;
        } catch (RuntimeException ex) {
            log.error("[FNOL] Could not save failed intake ({}) — policy={}", errorCode, payload.policyNumber(), ex);
            return null;
        }
    }

    private String newClaimId() {
        String id;
        do {
            byte[] bytes = new byte[4];
            RANDOM.nextBytes(bytes);
            id = "REJ-" + HexFormat.of().formatHex(bytes);
        } while (claims.existsById(id));
        return id;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
