package com.guidewire.fnol.agents;

import com.guidewire.fnol.common.Models.DamageAssessment;
import com.guidewire.fnol.common.Models.SecondOpinion;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * When Gemini cannot answer (quota, outage, no key, unreadable reply) but the photos were usable, the claim used to get
 * a placeholder assessment ("UNKNOWN"). The on-premise model (fnol-vision-ml) has already read the same photos in
 * parallel, so its reading replaces the placeholder: the damaged area, its probabilities and what the photos show.
 *
 * <p>Vehicle claims only: the car head is trained and measured out-of-fold on labelled photos
 * (tools/second-opinion/results/report-second-opinion.md); the home head is zero-shot. The estimate and severity stay
 * the placeholder's (the on-premise model has no cost data), and the gate flag {@code ON_PREMISE_VISION} always sends
 * the claim to an adjuster. The confidence shown is the model's own out-of-fold-calibrated top probability.
 */
public final class OnPremiseVision {

    /** visionSource prefix for assessments made on-premise. */
    public static final String SOURCE_PREFIX = "on-premise ";

    private OnPremiseVision() {
    }

    /** Gemini's placeholder for a failed call on usable photos (GeminiVisionProvider.fallback). */
    public static boolean isPlaceholder(DamageAssessment d) {
        Map<String, BigDecimal> p = d.damageTypeProbabilities();
        return d.visionSource() == null && "UNKNOWN".equals(d.damageType()) && "UNKNOWN".equals(d.photoQuality())
                && p != null && p.size() == 1 && p.containsKey("UNKNOWN");
    }

    public static boolean isOnPremise(DamageAssessment d) {
        return d != null && d.visionSource() != null && d.visionSource().startsWith(SOURCE_PREFIX);
    }

    /** The on-premise reading in place of Gemini's placeholder; any other assessment is returned unchanged. */
    public static DamageAssessment substitute(DamageAssessment d, SecondOpinion so, String lineOfBusiness) {
        if (!"AUTO".equalsIgnoreCase(lineOfBusiness) || so == null || !isPlaceholder(d)
                || so.damageProbabilities() == null || so.damageProbabilities().isEmpty()) {
            return d;
        }
        Map.Entry<String, BigDecimal> top = argmax(so.damageProbabilities());
        Map.Entry<String, BigDecimal> subject = so.subjectProbabilities() == null || so.subjectProbabilities().isEmpty()
                ? null : argmax(so.subjectProbabilities());
        return new DamageAssessment(top.getKey(), d.severity(), d.estimatedDamage(),
                top.getValue().setScale(2, RoundingMode.HALF_UP), d.photoQuality(), Map.copyOf(so.damageProbabilities()),
                null, subject == null ? null : subject.getKey(), d.privacy(), SOURCE_PREFIX + so.model());
    }

    private static Map.Entry<String, BigDecimal> argmax(Map<String, BigDecimal> p) {
        return p.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow();
    }
}
