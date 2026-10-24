package com.guidewire.fnol.agents;

import com.guidewire.fnol.common.Models.*;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * Reserve = estimated damage × 1.15 (ULAE factor), with a confidence score: the vision confidence
 * weighted by how usable the photo was. Weights are prototype values.
 */
@Component
public class ReserveCalculatorAgent implements FNOLAgent<DamageAssessment, ReserveResult> {

    static final BigDecimal FACTOR = new BigDecimal("1.15");

    private static final Map<String, BigDecimal> PHOTO_QUALITY_WEIGHT = Map.of(
            "CLEAR", new BigDecimal("1.00"),
            "PARTIAL", new BigDecimal("0.85"),
            "BLURRY", new BigDecimal("0.70"),
            "UNUSABLE", new BigDecimal("0.50"));
    private static final BigDecimal UNKNOWN_QUALITY_WEIGHT = new BigDecimal("0.80");

    @Override
    public ReserveResult execute(DamageAssessment d) {
        BigDecimal reserve = d.estimatedDamage().multiply(FACTOR).setScale(0, RoundingMode.HALF_UP);

        String quality = d.photoQuality() == null ? "UNKNOWN" : d.photoQuality();
        BigDecimal weight = PHOTO_QUALITY_WEIGHT.getOrDefault(quality, UNKNOWN_QUALITY_WEIGHT);
        BigDecimal visionConfidence = d.confidence() == null ? BigDecimal.ZERO : d.confidence();
        BigDecimal confidence = visionConfidence.multiply(weight).min(BigDecimal.ONE).setScale(2, RoundingMode.HALF_UP);

        return new ReserveResult(d.estimatedDamage(), FACTOR, reserve, confidence,
                "vision confidence " + visionConfidence + " × photo quality " + quality + " weight " + weight);
    }
}
