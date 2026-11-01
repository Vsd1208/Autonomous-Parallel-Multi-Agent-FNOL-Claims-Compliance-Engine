package com.guidewire.fnol.agents;

import com.guidewire.fnol.common.Models.DamageAssessment;
import com.guidewire.fnol.common.Models.SecondOpinion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OnPremiseVisionTest {

    /** GeminiVisionProvider's placeholder after a failed call on usable photos. */
    private static final DamageAssessment PLACEHOLDER = new DamageAssessment("UNKNOWN", "MODERATE",
            new BigDecimal("3500"), new BigDecimal("0.60"), "UNKNOWN", Map.of("UNKNOWN", BigDecimal.ONE));

    private static final SecondOpinion OPINION = new SecondOpinion("second-opinion v2 (clip + dinov2-base)",
            Map.of("SIDE_PANEL", new BigDecimal("0.7240"), "FRONT_BUMPER", new BigDecimal("0.2000"),
                    "REAR_BUMPER", new BigDecimal("0.0760")),
            Map.of("VEHICLE", new BigDecimal("0.97"), "BUILDING", new BigDecimal("0.03")));

    @Test
    @DisplayName("Gemini failed on usable vehicle photos: the on-premise reading replaces the placeholder")
    void replacesThePlaceholder() {
        DamageAssessment d = OnPremiseVision.substitute(PLACEHOLDER, OPINION, "AUTO");
        assertThat(d.damageType()).isEqualTo("SIDE_PANEL");
        assertThat(d.confidence()).isEqualByComparingTo("0.72");
        assertThat(d.damageTypeProbabilities()).containsEntry("FRONT_BUMPER", new BigDecimal("0.2000"));
        assertThat(d.photoSubject()).isEqualTo("VEHICLE");
        // No cost data on-premise: the placeholder's estimate and severity stay, marked by the source.
        assertThat(d.estimatedDamage()).isEqualByComparingTo("3500");
        assertThat(d.severity()).isEqualTo("MODERATE");
        assertThat(OnPremiseVision.isOnPremise(d)).isTrue();
        assertThat(d.visionSource()).startsWith(OnPremiseVision.SOURCE_PREFIX).contains("dinov2");
    }

    @Test
    @DisplayName("Left alone: a Gemini answer, a home claim, no photo, or no second opinion")
    void otherwiseUnchanged() {
        DamageAssessment gemini = new DamageAssessment("FRONT_BUMPER", "MODERATE", new BigDecimal("4200"),
                new BigDecimal("0.91"), "CLEAR", Map.of("FRONT_BUMPER", new BigDecimal("0.9"))).withVisionSource("gemini");
        assertThat(OnPremiseVision.substitute(gemini, OPINION, "AUTO")).isSameAs(gemini);
        assertThat(OnPremiseVision.substitute(PLACEHOLDER, OPINION, "HOME")).isSameAs(PLACEHOLDER);
        assertThat(OnPremiseVision.substitute(PLACEHOLDER, null, "AUTO")).isSameAs(PLACEHOLDER);
        DamageAssessment noPhoto = new DamageAssessment("UNKNOWN", "MODERATE", new BigDecimal("3500"),
                new BigDecimal("0.60"), "UNUSABLE", Map.of("UNKNOWN", BigDecimal.ONE));
        assertThat(OnPremiseVision.substitute(noPhoto, OPINION, "AUTO")).isSameAs(noPhoto);
        assertThat(OnPremiseVision.isOnPremise(gemini)).isFalse();
        assertThat(OnPremiseVision.isOnPremise(PLACEHOLDER)).isFalse();
    }
}
