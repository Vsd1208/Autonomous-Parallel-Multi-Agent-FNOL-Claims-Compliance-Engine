
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
            String channel   // intake source: WEB | MOBILE | PHONE | API (optional)
    ) {}

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
            BigDecimal reserve
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
            BigDecimal recommendedReserve
    ) {}

    public record PIIResult(
            String redactedDescription,
            List<String> detectedTypes,
            boolean piiDetected,
            BigDecimal redactionConfidence
    ) {}

    public record DeadlineResult(
            String state,
            LocalDate deadline,
            String ruleSource,
            String disclaimer
    ) {}

    public record SubrogationResult(
            int score,
            String recommendedAction,
            Map<String, Integer> factors
    ) {}

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
            Boolean requiresHumanValidation,   // true when HIGH-risk or low-confidence
            List<String> notes
    ) {}

    public record ApiError(
            Instant timestamp,
            int status,
            String error,
            String message,
            String path
    ) {}

    public record AdjusterReviewRequest(
            @NotBlank String decision,
            @NotBlank String adjusterId,
            String notes
    ) {}

    public record AdjusterReviewResponse(
            String claimId,
            String status,
            String adjusterDecision,
            String adjusterId,
            String adjusterNotes,
            Instant reviewedAt
    ) {}

    public record ClaimCreateRequest(
            String policyNumber,
            LocalDate incidentDate,
            String state,
            BigDecimal estimatedDamage,
            BigDecimal reserve
    ) {}

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
