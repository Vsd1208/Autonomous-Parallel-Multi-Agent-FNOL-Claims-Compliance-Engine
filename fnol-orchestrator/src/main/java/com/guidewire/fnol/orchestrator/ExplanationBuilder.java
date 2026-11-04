package com.guidewire.fnol.orchestrator;

import com.guidewire.fnol.agents.OnPremiseVision;
import com.guidewire.fnol.common.Models.*;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
        return build(coverage, damage, reserve, pii, subrogation, historyContext, null);
    }

    public ExplanationObject build(
            PolicyValidationResult coverage,
            DamageAssessment damage,
            ReserveResult reserve,
            PIIResult pii,
            SubrogationResult subrogation,
            PolicyHistoryContext historyContext,
            SecondOpinion secondOpinion) {

        SeverityReading severity = readSeverity(damage, secondOpinion, reserve);
        String severityGate = severity.gate();
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
                "Policy must have been in force at the date of loss, with a coverage the line-of-business rule accepts"));

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
                "Recommended reserve must be <= $" + stp.getReserveCeiling().toPlainString() + " for STP"
                        + reserveSteps(reserve.breakdown())));

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
            summary = "Claim severity is HIGH (" + severity.reason() + "); escalate to human claim handler regardless of factors";
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

    /** The calculation in words, for the WHY panel: the base and its source, the AI adjustment, then the caps. */
    static String reserveSteps(ReserveBreakdown b) {
        if (b == null) {
            return "";
        }
        if (!"AVERAGE_TABLE".equals(b.method())) {
            return ". " + b.adjustmentReason();
        }
        if (b.tableEntryMissing()) {
            return ". " + b.adjustmentReason() + " (" + b.baseRow() + ")";
        }
        String caps = b.capsApplied().isEmpty() ? "" : ". " + String.join(". ", b.capsApplied());
        return ". Base $" + b.baseReserve() + " from " + b.baseRow() + " (" + b.baseSource() + "). "
                + b.adjustmentReason() + ": $" + b.supportedAmount() + caps;
    }

    private static final Map<String, String> GATE_FOR_LABEL = Map.of("MINOR", "LOW", "MODERATE", "MEDIUM", "SEVERE", "HIGH");
    private static final Map<String, String> GATE_FOR_EXTENT = Map.of("SMALL", "LOW", "MEDIUM", "MEDIUM", "LARGE", "HIGH");
    private static final List<String> ORDER = List.of("LOW", "MEDIUM", "HIGH");

    /** The severity gate, why it reached HIGH if it did, and anything the adjuster should be told. */
    public record SeverityReading(String gate, String reason, List<String> notes) {}

    /**
     * Severity from the vision label, never from the AI's dollar figure (ADR-016). When the second opinion's visible extent
     * reads differently, or there is no usable label, the more severe reading stands, so a doubtful claim is never under-routed.
     * A table-based reserve above fnol.stp.medium-severity-max is HIGH whatever the reading.
     */
    public SeverityReading readSeverity(DamageAssessment damage, SecondOpinion secondOpinion, ReserveResult reserve) {
        List<String> notes = new ArrayList<>();
        String label = damage.severity();
        String gate = label == null ? null : GATE_FOR_LABEL.get(label);
        String reason = "the vision label is " + label;
        if (gate == null) {
            gate = "HIGH";
            reason = "the vision reading has no usable severity label";
            notes.add("SEVERITY: the vision reading has no usable severity label, so the claim is treated as HIGH; assess it from the photos.");
        } else if (secondOpinion != null && secondOpinion.extent() != null && !OnPremiseVision.isOnPremise(damage)) {
            String band = secondOpinion.extent().band();
            String extentGate = GATE_FOR_EXTENT.get(band);
            if (extentGate != null && !extentGate.equals(gate)) {
                String moreSevere = ORDER.indexOf(extentGate) > ORDER.indexOf(gate) ? extentGate : gate;
                notes.add("SEVERITY: Gemini's label is " + label + " but the second-opinion model sees " + band + " visible damage, "
                        + "so the more severe reading (" + moreSevere + ") stands. Visible extent also depends on how close the photo was taken.");
                if (!moreSevere.equals(gate)) {
                    reason = "the second-opinion model sees " + band + " visible damage, more severe than the label " + label;
                }
                gate = moreSevere;
            }
        }
        ReserveBreakdown steps = reserve == null ? null : reserve.breakdown();
        if (steps != null && "AVERAGE_TABLE".equals(steps.method()) && !steps.tableEntryMissing() && steps.supportedAmount() != null
                && steps.supportedAmount().compareTo(stp.getMediumSeverityMax()) > 0) {
            if (!"HIGH".equals(gate)) {
                reason = "the table-based reserve of $" + steps.supportedAmount() + " is above $" + stp.getMediumSeverityMax().toPlainString();
                notes.add("SEVERITY: HIGH because " + reason + ".");
            }
            gate = "HIGH";
        }
        return new SeverityReading(gate, reason, List.copyOf(notes));
    }

    private static String status(boolean pass) {
        return pass ? "PASS" : "FAIL";
    }
}
