package com.guidewire.fnol.agents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guidewire.fnol.common.Models.DamageAssessment;
import com.guidewire.fnol.common.Models.SecondOpinion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TrustScorerTest {

    private static JsonNode file(String promptSha, int images) throws Exception {
        return new ObjectMapper().readTree("""
                {"version": 1, "line": "AUTO", "model": "gemini-2.5-flash", "thinkingBudget": 0,
                 "promptSha256": "%s", "images": %d,
                 "withSecondOpinion": {"features": ["margin", "agreement", "specific"], "coef": [0.81, 0.75, 1.13], "intercept": 0.68},
                 "marginOnly": {"features": ["margin", "specific"], "coef": [0.91, 1.27], "intercept": 0.73},
                 "metrics": {"aucRaw": 0.5, "aucTrust": 0.73}}
                """.formatted(promptSha, images));
    }

    private static final DamageAssessment GEMINI = new DamageAssessment("FRONT_BUMPER", "MODERATE",
            new BigDecimal("4200"), new BigDecimal("0.90"), "CLEAR",
            Map.of("FRONT_BUMPER", new BigDecimal("0.85"), "SIDE_PANEL", new BigDecimal("0.15")), null, "VEHICLE");

    @Test
    @DisplayName("Used only when measured with the app's prompt, model and thinking budget on at least 50 images")
    void shippingRules() throws Exception {
        String sha = VisionConfidenceCalibrator.VEHICLE_PROMPT_SHA;
        assertThat(new TrustScorer(file(sha, 60), "gemini-2.5-flash", 0).isActive()).isTrue();
        assertThat(new TrustScorer(file("61a89427e0e132d9", 60), "gemini-2.5-flash", 0).isActive()).isFalse();
        assertThat(new TrustScorer(file(sha, 33), "gemini-2.5-flash", 0).isActive()).isFalse();
        assertThat(new TrustScorer(file(sha, 60), "gemini-2.5-pro", 0).isActive()).isFalse();
        assertThat(new TrustScorer(file(sha, 60), "gemini-2.5-flash", 1024).isActive()).isFalse();
        assertThat(TrustScorer.none().apply(GEMINI, null, "AUTO")).isSameAs(GEMINI);
    }

    @Test
    @DisplayName("Replaces Gemini's confidence with the calibrated score; disagreement lowers it; Gemini's value is kept")
    void scores() throws Exception {
        TrustScorer scorer = new TrustScorer(file(VisionConfidenceCalibrator.VEHICLE_PROMPT_SHA, 60), "gemini-2.5-flash", 0);
        SecondOpinion agrees = new SecondOpinion("so", Map.of("FRONT_BUMPER", new BigDecimal("0.80")), Map.of());
        SecondOpinion disagrees = new SecondOpinion("so", Map.of("FRONT_BUMPER", new BigDecimal("0.05")), Map.of());

        DamageAssessment high = scorer.apply(GEMINI, agrees, "AUTO");
        // 1 / (1 + e^-(0.68 + 0.81*0.70 + 0.75*0.80 + 1.13)) = 0.95
        assertThat(high.confidence()).isEqualByComparingTo("0.95");
        assertThat(high.rawConfidence()).isEqualByComparingTo("0.90");
        assertThat(high.photoSubject()).isEqualTo("VEHICLE");
        assertThat(scorer.apply(GEMINI, disagrees, "AUTO").confidence()).isLessThan(high.confidence());
        // Without a second opinion the margin-only model is used.
        assertThat(scorer.apply(GEMINI, null, "AUTO").confidence()).isEqualByComparingTo("0.93");
    }

    @Test
    @DisplayName("Home claims and fallback assessments (no probabilities) keep their confidence")
    void notApplied() throws Exception {
        TrustScorer scorer = new TrustScorer(file(VisionConfidenceCalibrator.VEHICLE_PROMPT_SHA, 60), "gemini-2.5-flash", 0);
        assertThat(scorer.apply(GEMINI, null, "HOME")).isSameAs(GEMINI);
        DamageAssessment fallback = new DamageAssessment("UNKNOWN", "MODERATE", new BigDecimal("3500"),
                new BigDecimal("0.60"), "UNUSABLE", Map.of());
        assertThat(scorer.apply(fallback, null, "AUTO")).isSameAs(fallback);
    }

    @Test
    @DisplayName("The shipped trust score (namedMargin): an UNKNOWN answer never gets more trust than a 'multiple areas' one")
    void shippedScoreIgnoresMarginWithoutANamedArea() {
        TrustScorer scorer = new TrustScorer(true, "gemini-2.5-flash", 0);   // the real trust-score.json
        assertThat(scorer.isActive()).isTrue();
        // UNKNOWN puts everything on "unknown" (margin 1.0) and is always wrong in evaluation.
        DamageAssessment unknown = new DamageAssessment("UNKNOWN", "MODERATE", new BigDecimal("3000"),
                new BigDecimal("0.70"), "CLEAR", Map.of("UNKNOWN", new BigDecimal("1.0")), null, "VEHICLE");
        DamageAssessment multiple = new DamageAssessment("MULTIPLE", "MODERATE", new BigDecimal("3000"),
                new BigDecimal("0.90"), "CLEAR", Map.of("SIDE_PANEL", new BigDecimal("0.40"),
                "MULTIPLE", new BigDecimal("0.40"), "FRONT_BUMPER", new BigDecimal("0.20")), null, "VEHICLE");
        BigDecimal unknownTrust = scorer.apply(unknown, null, "AUTO").confidence();
        BigDecimal multipleTrust = scorer.apply(multiple, null, "AUTO").confidence();
        assertThat(unknownTrust).isEqualByComparingTo(multipleTrust);
        assertThat(unknownTrust).isLessThan(new BigDecimal("0.85"));   // never straight through on confidence

        // A named area: a clearer answer and an agreeing second opinion both raise the score.
        SecondOpinion agrees = new SecondOpinion("so", Map.of("FRONT_BUMPER", new BigDecimal("0.80")), Map.of());
        SecondOpinion disagrees = new SecondOpinion("so", Map.of("FRONT_BUMPER", new BigDecimal("0.05")), Map.of());
        DamageAssessment unclear = new DamageAssessment("FRONT_BUMPER", "MODERATE", new BigDecimal("4200"),
                new BigDecimal("0.90"), "CLEAR",
                Map.of("FRONT_BUMPER", new BigDecimal("0.55"), "SIDE_PANEL", new BigDecimal("0.45")), null, "VEHICLE");
        assertThat(scorer.apply(GEMINI, agrees, "AUTO").confidence())
                .isGreaterThan(scorer.apply(unclear, agrees, "AUTO").confidence())
                .isGreaterThan(scorer.apply(GEMINI, disagrees, "AUTO").confidence());
    }
}
