package com.guidewire.fnol.orchestrator;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * {@code fnol.stp.*}: straight-through-processing rules and the human-oversight switch.
 *
 * <p>The thresholds are prototype values; the business owner of the STP rules must confirm them.
 */
@Component
@ConfigurationProperties(prefix = "fnol.stp")
public class StpProperties {

    /** Minimum vision confidence for the AI Confidence factor. */
    private BigDecimal confidence = new BigDecimal("0.85");
    /** Maximum recommended reserve for the Reserve Amount factor. */
    private BigDecimal reserveCeiling = new BigDecimal("25000");
    /** Subrogation score must be strictly below this for the Subrogation Risk factor. */
    private int subrogationMax = 70;
    /** Estimated damage up to this is LOW severity. */
    private BigDecimal lowSeverityMax = new BigDecimal("5000");
    /** Estimated damage up to this is MEDIUM severity; above is HIGH and always goes to a human. */
    private BigDecimal mediumSeverityMax = new BigDecimal("20000");
    /** Minimum PII redaction confidence for a claim with detected PII to pass the Compliance factor. */
    private BigDecimal piiRedactionConfidence = new BigDecimal("0.90");
    /**
     * When true (default), an STP-eligible claim is pre-filed in ClaimCenter but saved as
     * PENDING_ADVISORY_REVIEW until an adjuster confirms it. When false it is STRAIGHT_THROUGH_PROCESSING.
     */
    private boolean requireAdvisoryReview = true;
    /**
     * How far the claimed amount may exceed the AI estimate before the claim goes to an adjuster
     * (0.10 = 10%). Within it, the AI estimate is still the most that is reserved automatically.
     */
    private BigDecimal claimedAmountTolerance = new BigDecimal("0.10");
    /**
     * Smallest gap between the two highest damage-type probabilities for the AI's answer to be relied on.
     * Measured on labelled photos (tools/vision-eval): every answer with a gap of 0.60 or more was right, and
     * every wrong answer that passed the confidence factor had a gap below 0.30, at a reported confidence
     * of 0.90-0.95. Gemini's own confidence does not separate them, so this is checked separately.
     */
    private BigDecimal minProbabilityMargin = new BigDecimal("0.30");

    /**
     * MODEL_DISAGREEMENT when the second-opinion model gives Gemini's damage type less than this. 0.15 caught the one
     * confident wrong answer in the car evaluation while flagging 2 of 35 right answers (out-of-fold, small sample).
     */
    private BigDecimal secondOpinionMinProbability = new BigDecimal("0.15");

    public BigDecimal getConfidence() { return confidence; }
    public void setConfidence(BigDecimal confidence) { this.confidence = confidence; }

    public BigDecimal getReserveCeiling() { return reserveCeiling; }
    public void setReserveCeiling(BigDecimal reserveCeiling) { this.reserveCeiling = reserveCeiling; }

    public int getSubrogationMax() { return subrogationMax; }
    public void setSubrogationMax(int subrogationMax) { this.subrogationMax = subrogationMax; }

    public BigDecimal getLowSeverityMax() { return lowSeverityMax; }
    public void setLowSeverityMax(BigDecimal lowSeverityMax) { this.lowSeverityMax = lowSeverityMax; }

    public BigDecimal getMediumSeverityMax() { return mediumSeverityMax; }
    public void setMediumSeverityMax(BigDecimal mediumSeverityMax) { this.mediumSeverityMax = mediumSeverityMax; }

    public BigDecimal getPiiRedactionConfidence() { return piiRedactionConfidence; }
    public void setPiiRedactionConfidence(BigDecimal piiRedactionConfidence) { this.piiRedactionConfidence = piiRedactionConfidence; }

    /**
     * FRAUD_ANOMALY when the fraud anomaly score reaches this many points. Empty uses the threshold fitted in
     * fraud-anomaly.json (a 5% referral budget on synthetic data). The flag only routes to an adjuster.
     */
    private BigDecimal fraudAnomalyThreshold;
    /** false turns the fraud anomaly score off (no score, no FRAUD_ANOMALY flag). */
    private boolean fraudAnomalyEnabled = true;

    public BigDecimal getFraudAnomalyThreshold() { return fraudAnomalyThreshold; }
    public void setFraudAnomalyThreshold(BigDecimal v) { this.fraudAnomalyThreshold = v; }
    public boolean isFraudAnomalyEnabled() { return fraudAnomalyEnabled; }
    public void setFraudAnomalyEnabled(boolean v) { this.fraudAnomalyEnabled = v; }

    public BigDecimal getSecondOpinionMinProbability() { return secondOpinionMinProbability; }
    public void setSecondOpinionMinProbability(BigDecimal v) { this.secondOpinionMinProbability = v; }

    public BigDecimal getMinProbabilityMargin() { return minProbabilityMargin; }
    public void setMinProbabilityMargin(BigDecimal minProbabilityMargin) { this.minProbabilityMargin = minProbabilityMargin; }

    public BigDecimal getClaimedAmountTolerance() { return claimedAmountTolerance; }
    public void setClaimedAmountTolerance(BigDecimal claimedAmountTolerance) { this.claimedAmountTolerance = claimedAmountTolerance; }

    public boolean isRequireAdvisoryReview() { return requireAdvisoryReview; }
    public void setRequireAdvisoryReview(boolean requireAdvisoryReview) { this.requireAdvisoryReview = requireAdvisoryReview; }
}
