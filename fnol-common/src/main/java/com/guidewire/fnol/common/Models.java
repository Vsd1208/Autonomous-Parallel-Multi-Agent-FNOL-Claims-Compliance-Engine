
package com.guidewire.fnol.common;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

public final class Models {
    private Models() {}

    public record FNOLPayload(
            @NotBlank String policyNumber,
            @NotNull LocalDate incidentDate,
            @NotBlank String state,
            @NotBlank String description,
            String claimantName,
            String claimantDob,
            String claimantSsn,
            List<String> evidenceUris,
            String channel,         // intake source: WEB | MOBILE | PHONE | API (optional)
            String claimantPhone,   // optional; PII — never persisted or logged
            String claimantEmail    // optional; PII — never persisted or logged
    ) {
        /** Payload without claimant contact details. */
        public FNOLPayload(String policyNumber, LocalDate incidentDate, String state, String description,
                           String claimantName, String claimantDob, String claimantSsn,
                           List<String> evidenceUris, String channel) {
            this(policyNumber, incidentDate, state, description, claimantName, claimantDob, claimantSsn,
                    evidenceUris, channel, null, null);
        }
    }

    public record Coverage(
            String type,
            BigDecimal limit,
            BigDecimal deductible
    ) {}

    public record Policy(
            String policyNumber,
            String policyType,
            String status,
            LocalDate effectiveDate,
            LocalDate expirationDate,
            String insuredName,
            String state,
            String riskDescription,
            List<Coverage> coverages
    ) {}

    public record ClaimHistory(
            String claimId,
            LocalDate lossDate,
            String status,
            BigDecimal paidAmount,
            String description
    ) {}

    public record Claim(
            String claimId,
            String status,
            String policyNumber,
            LocalDate incidentDate,
            String state,
            BigDecimal estimatedDamage,
            BigDecimal reserve,
            String notes,
            List<ClaimAttachment> attachments,
            List<ClaimReviewAction> reviewActions
    ) {
        public Claim(String claimId, String status, String policyNumber, LocalDate incidentDate, String state,
                     BigDecimal estimatedDamage, BigDecimal reserve) {
            this(claimId, status, policyNumber, incidentDate, state, estimatedDamage, reserve, null, List.of(), List.of());
        }
    }

    /**
     * ClaimCenter update after an adjuster decision (PUT /cc/claims/{id}). Each update is also
     * tracked as a review action on the claim.
     */
    public record ClaimUpdateRequest(
            String status,            // ClaimCenter status, e.g. OPEN, DENIED, PENDING_INFORMATION
            String notes,
            String action,            // the adjuster decision that caused the update
            String adjusterId,
            BigDecimal adjustedReserve // set when the adjuster changed the AI-recommended reserve
    ) {
        public ClaimUpdateRequest(String status, String notes) {
            this(status, notes, null, null, null);
        }
    }

    /** One tracked review action on a ClaimCenter claim. */
    public record ClaimReviewAction(
            Instant at,
            String action,
            String status,
            String adjusterId,
            String notes,
            BigDecimal adjustedReserve
    ) {}

    /** A document attached to a ClaimCenter claim (POST /cc/claims/{id}/attachments). */
    public record ClaimAttachment(
            String name,
            String contentType,
            Object body          // JSON document
    ) {}

    public record DamageAssessment(
            String damageType,
            String severity,
            BigDecimal estimatedDamage,
            BigDecimal confidence,
            String photoQuality,                          // "CLEAR" | "BLURRY" | "PARTIAL" | "UNUSABLE" | "UNKNOWN"
            Map<String, BigDecimal> damageTypeProbabilities  // e.g. {"FRONT_BUMPER": 0.87, "SIDE_PANEL": 0.09, ...}
    ) {
        /**
         * Backward-compatible factory used by internal stubs and tests that do not
         * supply Gemini vision metadata. Defaults photoQuality to "UNKNOWN" and
         * probability map to empty.
         */
        public static DamageAssessment of(String damageType, String severity,
                                          BigDecimal estimatedDamage, BigDecimal confidence) {
            return new DamageAssessment(damageType, severity, estimatedDamage, confidence,
                    "UNKNOWN", Map.of());
        }
    }

    public record ReserveResult(
            BigDecimal estimatedDamage,
            BigDecimal factor,
            BigDecimal recommendedReserve,
            BigDecimal confidence,      // 0-1: vision confidence weighted by photo quality
            String confidenceBasis
    ) {
        public ReserveResult(BigDecimal estimatedDamage, BigDecimal factor, BigDecimal recommendedReserve) {
            this(estimatedDamage, factor, recommendedReserve, null, null);
        }
    }

    public record PIIResult(
            String redactedDescription,
            List<String> detectedTypes,
            boolean piiDetected,
            BigDecimal redactionConfidence
    ) {}

    public record DeadlineResult(
            String state,
            LocalDate deadline,         // kept for existing clients; equals acknowledgeBy
            String ruleSource,
            String disclaimer,
            LocalDate acknowledgeBy,
            LocalDate decisionBy,
            String urgencyFlag          // OK | WARNING (acknowledgeBy within 3 days) | CRITICAL (past due)
    ) {}

    public record SubrogationResult(
            int score,
            String recommendedAction,          // INVESTIGATE | MONITOR; PENDING_SEVERITY in Branch B
            Map<String, Integer> factors,
            List<String> complianceNotes       // liability and recovery notes for the adjuster (prototype guidance)
    ) {
        public SubrogationResult(int score, String recommendedAction, Map<String, Integer> factors) {
            this(score, recommendedAction, factors, List.of());
        }
    }

    public record PolicyValidationResult(
            String policyNumber,
            boolean policyFound,
            boolean policyActive,
            boolean relevantCoverageFound,
            boolean covered,
            String coverageType,
            BigDecimal coverageLimit,
            BigDecimal deductible,
            List<String> reasoning
    ) {}

    public record AuditRecord(
            String eventId,
            String claimId,
            String agentName,
            Instant timestamp,
            String inputHash,
            String outputHash,
            String status
    ) {}

    public record BranchAResult(
            DamageAssessment damageAssessment,
            PolicyValidationResult coverage,
            ReserveResult reserve
    ) {}

    public record BranchBResult(
            PIIResult pii,
            DeadlineResult deadline,
            SubrogationResult subrogation,
            List<AuditRecord> audit
    ) {}

    public record FNOLResponse(
            String claimId,
            String status,
            BranchAResult branchA,
            BranchBResult branchB,
            PolicyHistoryContext policyContext,
            ExplanationObject explanation,
            String severityGate,
            Boolean eligibleForAdvisoryPrefill,
            Boolean requiresHumanValidation,   // true when any factor fails, HIGH risk, or any input flag
            List<String> notes,
            List<String> inputFlags,           // Human Review Point 1 flags, e.g. MISSING_EVIDENCE
            String ccSyncStatus,               // SYNCED | FAILED: were the advisory and evidence attached in ClaimCenter
            List<String> gateFlags             // reconciliation-gate consistency flags, e.g. RESERVE_EXCEEDS_COVERAGE_LIMIT
    ) {}

    public record ApiError(
            Instant timestamp,
            int status,
            String error,
            String message,
            String path,
            String claimId     // set when a failed intake was saved for human attention (REJ-...)
    ) {
        public ApiError(Instant timestamp, int status, String error, String message, String path) {
            this(timestamp, status, error, message, path, null);
        }
    }

    /**
     * The only view of an intake that may be hashed into the audit trail: no claimant name,
     * DOB, SSN or raw description, so audit hashes can never be matched against raw PII.
     */
    public record SanitizedIntake(
            String policyNumber,
            String state,
            LocalDate incidentDate,
            String redactedDescription
    ) {
        public static SanitizedIntake of(FNOLPayload p, String redactedDescription) {
            return new SanitizedIntake(p.policyNumber(), p.state(), p.incidentDate(), redactedDescription);
        }
    }

    /**
     * Human Review Point 3. {@code adjustedReserve} lets the adjuster modify the AI finding; it
     * requires an {@code overrideReason}.
     */
    public record AdjusterReviewRequest(
            @NotBlank String decision,
            @NotBlank String adjusterId,
            String notes,
            @PositiveOrZero BigDecimal adjustedReserve,
            @Size(max = 500) String overrideReason
    ) {
        public AdjusterReviewRequest(String decision, String adjusterId, String notes) {
            this(decision, adjusterId, notes, null, null);
        }
    }

    public record AdjusterReviewResponse(
            String claimId,
            String status,
            String adjusterDecision,
            String adjusterId,
            String adjusterNotes,
            Instant reviewedAt,
            BigDecimal adjustedReserve,
            String overrideReason
    ) {}

    public record ClaimCreateRequest(
            String policyNumber,
            LocalDate incidentDate,
            String state,
            BigDecimal estimatedDamage,
            BigDecimal reserve,
            String initialStatus      // DRAFT_PENDING_REVIEW until a human decides; OPEN for unreviewed STP
    ) {
        public ClaimCreateRequest(String policyNumber, LocalDate incidentDate, String state,
                                  BigDecimal estimatedDamage, BigDecimal reserve) {
            this(policyNumber, incidentDate, state, estimatedDamage, reserve, null);
        }
    }

    public record ClaimCreateResponse(
            String claimId,
            String status
    ) {}

    public record PolicyHistoryContext(
            int totalPriorClaims,
            int claimsLast6Months,
            int claimsLast12Months,
            LocalDate lastClaimDate,
            String lastClaimType,
            BigDecimal lastClaimAmount,
            int openClaimsCount,
            String claimFrequencyRisk
    ) {}

    public record ExplanationFactor(
            String factor,
            String value,
            String status,
            String detail
    ) {}

    public record ExplanationObject(
            String decision,
            String summary,
            List<ExplanationFactor> factors,
            String recommendedAction,
            String severityGate
    ) {}
}
