package com.guidewire.fnol.orchestrator;

import com.guidewire.fnol.common.Models.*;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Component
public class ExplanationBuilder {

    public ExplanationObject build(
            PolicyValidationResult coverage,
            DamageAssessment damage,
            ReserveResult reserve,
            PIIResult pii,
            SubrogationResult subrogation,
            PolicyHistoryContext historyContext) {

        // Calculate severity FIRST
        String severityGate = calculateClaimSeverity(damage);

        // Build 6 factors (same as before)
        List<ExplanationFactor> factors = new ArrayList<>();

        // Factor 1: AI Confidence
        BigDecimal confidenceThreshold = new BigDecimal("0.85");
        boolean aiConfidencePassed = damage.confidence().compareTo(confidenceThreshold) >= 0;
        factors.add(new ExplanationFactor("AI Confidence",
                String.format("%.2f", damage.confidence()),
                aiConfidencePassed ? "PASS" : "FAIL",
                "Confidence score must be >= 0.85"));

        // Factor 2: Coverage Status
        boolean coveragePassed = coverage.covered();
        factors.add(new ExplanationFactor("Coverage Status",
                coverage.coverageType() != null ? coverage.coverageType() : "NONE",
                coveragePassed ? "PASS" : "FAIL",
                "Relevant coverage must be active and matched"));

        // Factor 3: Compliance (PII)
        boolean compliancePassed = !pii.piiDetected();
        factors.add(new ExplanationFactor("Compliance (PII)",
                pii.piiDetected() ? "DETECTED" : "CLEAN",
                compliancePassed ? "PASS" : "FAIL",
                "PII must be redacted or absent"));

        // Factor 4: Reserve Amount
        BigDecimal stpCeiling = new BigDecimal("25000");
        boolean reservePassed = reserve.recommendedReserve().compareTo(stpCeiling) <= 0;
        factors.add(new ExplanationFactor("Reserve Amount",
                "$" + reserve.recommendedReserve(),
                reservePassed ? "PASS" : "FAIL",
                "Reserve must be <= $25,000 for advisory prefill"));

        // Factor 5: Claim History
        boolean historyPassed = !"HIGH".equals(historyContext.claimFrequencyRisk());
        factors.add(new ExplanationFactor("Claim History",
                historyContext.claimFrequencyRisk() + " (" + historyContext.claimsLast12Months() + " in 12 months)",
                historyPassed ? "PASS" : "FAIL",
                "Frequency risk must be LOW or MEDIUM"));

        // Factor 6: Subrogation Risk
        boolean subrogationPassed = subrogation.score() < 70;
        factors.add(new ExplanationFactor("Subrogation Risk",
                String.valueOf(subrogation.score()),
                subrogationPassed ? "PASS" : "FAIL",
                "Subrogation score must be < 70"));

        // Determine recommended action based on severity gate
        boolean allPassed = factors.stream().allMatch(f -> "PASS".equals(f.status()));
        String recommendedAction;
        String decision;
        String summary;

        if ("LOW".equals(severityGate)) {
            // LOW severity: only prefill if all factors pass
            if (allPassed) {
                decision = "ADVISORY_RECOMMENDED_ACTION";
                recommendedAction = "PREFILL_LOW_SEVERITY_CLAIM";
                summary = "All criteria met for advisory-guided pre-population of low-severity claim";
            } else {
                decision = "ADVISORY_RECOMMENDED_ACTION";
                recommendedAction = "ESCALATE_TO_HUMAN";
                summary = "One or more criteria failed; escalate to human claim handler for review";
            }
        } else {
            // MEDIUM/HIGH severity: always escalate
            decision = "ADVISORY_RECOMMENDED_ACTION";
            recommendedAction = "ESCALATE_TO_HUMAN_HIGH_RISK_SEVERITY";
            summary = "Claim severity is " + severityGate + "; escalate to human claim handler regardless of factors";
        }

        return new ExplanationObject(decision, summary, factors, recommendedAction, severityGate);
    }

    /**
     * Calculate claim severity based on estimated damage
     */
    public String calculateClaimSeverity(DamageAssessment damage) {
        BigDecimal estimatedDamage = damage.estimatedDamage();
        BigDecimal lowThreshold = new BigDecimal("5000");
        BigDecimal mediumThreshold = new BigDecimal("20000");

        if (estimatedDamage.compareTo(lowThreshold) <= 0) {
            return "LOW";  // Eligible for advisory pre-population
        } else if (estimatedDamage.compareTo(mediumThreshold) <= 0) {
            return "MEDIUM";
        } else {
            return "HIGH";
        }
    }
}