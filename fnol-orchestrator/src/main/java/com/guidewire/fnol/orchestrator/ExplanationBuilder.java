package com.guidewire.fnol.orchestrator;

import com.guidewire.fnol.common.Models.*;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the six-factor WHY panel and the recommended route.
 *
 * <p>STP requires severity LOW or MEDIUM and all six factors passing. HIGH severity always goes
 * to a human. Thresholds come from {@link StpProperties} ({@code fnol.stp.*}).
 */
@Component
public class ExplanationBuilder {

    private final StpProperties stp;

    public ExplanationBuilder(StpProperties stp) {
        this.stp = stp;
    }

    public ExplanationObject build(
            PolicyValidationResult coverage,
            DamageAssessment damage,
            ReserveResult reserve,
            PIIResult pii,
            SubrogationResult subrogation,
            PolicyHistoryContext historyContext) {

        String severityGate = calculateClaimSeverity(damage);
        List<ExplanationFactor> factors = new ArrayList<>();

        // Factor 1: AI Confidence
        boolean aiConfidencePassed = damage.confidence().compareTo(stp.getConfidence()) >= 0;
        boolean calibrated = damage.rawConfidence() != null;
        factors.add(new ExplanationFactor("AI Confidence",
                String.format("%.2f", damage.confidence())
                        + (calibrated ? String.format(" (model said %.2f)", damage.rawConfidence()) : ""),
                status(aiConfidencePassed),
                "Vision confidence must be >= " + stp.getConfidence()
                        + (calibrated ? "; calibrated against labelled damage photos" : "")));

        // Factor 2: Coverage Status
        factors.add(new ExplanationFactor("Coverage Status",
                coverage.coverageType() != null ? coverage.coverageType() : "NONE",
                status(coverage.covered()),
                "Policy must be active with a coverage the line-of-business rule accepts"));

        // Factor 3: Compliance (PII) — redacted PII is acceptable. The PII guardrail has already
        // blocked raw SSNs and MRNs before this point; here we also require confident redaction.
        boolean redactedConfidently = pii.redactionConfidence() != null
                && pii.redactionConfidence().compareTo(stp.getPiiRedactionConfidence()) >= 0;
        boolean compliancePassed = !pii.piiDetected() || redactedConfidently;
        factors.add(new ExplanationFactor("Compliance (PII)",
                pii.piiDetected() ? "REDACTED " + pii.detectedTypes() : "CLEAN",
                status(compliancePassed),
                "No PII detected, or PII redacted with confidence >= " + stp.getPiiRedactionConfidence()));

        // Factor 4: Reserve Amount
        boolean reservePassed = reserve.recommendedReserve().compareTo(stp.getReserveCeiling()) <= 0;
        factors.add(new ExplanationFactor("Reserve Amount",
                "$" + reserve.recommendedReserve(),
                status(reservePassed),
                "Recommended reserve must be <= $" + stp.getReserveCeiling().toPlainString() + " for STP"));

        // Factor 5: Claim History
        boolean historyPassed = !"HIGH".equals(historyContext.claimFrequencyRisk());
        factors.add(new ExplanationFactor("Claim History",
                historyContext.claimFrequencyRisk() + " (" + historyContext.claimsLast12Months() + " in 12 months)",
                status(historyPassed),
                "Claim frequency risk must be LOW or MEDIUM"));

        // Factor 6: Subrogation Risk (scored at the gate with Branch A's real severity)
        boolean subrogationPassed = subrogation.score() < stp.getSubrogationMax();
        factors.add(new ExplanationFactor("Subrogation Risk",
                String.valueOf(subrogation.score()),
                status(subrogationPassed),
                "Subrogation score must be < " + stp.getSubrogationMax()
                        + "; higher scores go to a human to pursue recovery"));

        boolean allPassed = factors.stream().allMatch(f -> "PASS".equals(f.status()));
        String decision;
        String recommendedAction;
        String summary;

        if ("HIGH".equals(severityGate)) {
            decision = "ADVISORY_RECOMMENDED_ACTION";
            recommendedAction = "ESCALATE_TO_HUMAN_HIGH_RISK_SEVERITY";
            summary = "Claim severity is HIGH (estimated damage above $" + stp.getMediumSeverityMax().toPlainString()
                    + "); escalate to human claim handler regardless of factors";
        } else if (allPassed) {
            decision = "STRAIGHT_THROUGH";
            recommendedAction = "APPROVE_AND_STP";
            summary = "All criteria met for automated straight-through processing";
        } else {
            decision = "ADVISORY_RECOMMENDED_ACTION";
            recommendedAction = "ESCALATE_TO_HUMAN";
            summary = "One or more criteria failed; escalate to human claim handler for review";
        }

        return new ExplanationObject(decision, summary, factors, recommendedAction, severityGate);
    }

    /** LOW up to lowSeverityMax, MEDIUM up to mediumSeverityMax, otherwise HIGH (by estimated damage). */
    public String calculateClaimSeverity(DamageAssessment damage) {
        BigDecimal estimatedDamage = damage.estimatedDamage();
        if (estimatedDamage.compareTo(stp.getLowSeverityMax()) <= 0) {
            return "LOW";
        } else if (estimatedDamage.compareTo(stp.getMediumSeverityMax()) <= 0) {
            return "MEDIUM";
        }
        return "HIGH";
    }

    private static String status(boolean pass) {
        return pass ? "PASS" : "FAIL";
    }
}
