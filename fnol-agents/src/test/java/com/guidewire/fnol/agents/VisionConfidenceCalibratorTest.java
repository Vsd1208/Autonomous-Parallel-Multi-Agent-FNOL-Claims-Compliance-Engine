package com.guidewire.fnol.agents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.guidewire.fnol.common.Models.DamageAssessment;
import com.guidewire.fnol.common.Models.FNOLPayload;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VisionConfidenceCalibratorTest {

    private static JsonNode calibration(String model, int budget) throws Exception {
        return new ObjectMapper().readTree("""
                {"model": "%s", "thinkingBudget": %d, "images": 120, "dataset": "test",
                 "table": [{"from": 0.0, "to": 0.8, "calibrated": 0.40, "n": 10},
                           {"from": 0.85, "to": 0.9, "calibrated": 0.62, "n": 60},
                           {"from": 0.95, "to": 1.0, "calibrated": 0.71, "n": 30}]}
                """.formatted(model, budget));
    }

    @Test
    @DisplayName("A map measured with a different vehicle prompt is not applied; one with the current prompt is")
    void mapForAnotherPromptIsIgnored() throws Exception {
        ObjectNode other = (ObjectNode) calibration("gemini-2.5-flash", 0);
        other.put("promptSha256", "61a89427e0e132d9");   // v2-multiphoto, before photoSubject
        assertThat(new VisionConfidenceCalibrator(other, "gemini-2.5-flash", 0).isActive()).isFalse();
        ObjectNode current = (ObjectNode) calibration("gemini-2.5-flash", 0);
        current.put("promptSha256", VisionConfidenceCalibrator.VEHICLE_PROMPT_SHA);
        assertThat(new VisionConfidenceCalibrator(current, "gemini-2.5-flash", 0).isActive()).isTrue();
    }

    @Test
    @DisplayName("The app's prompt fingerprint is the one tools/vision-eval records (update the v3-subject run if this changes)")
    void promptFingerprintMatchesEvaluationTool() {
        // Computed by tools/vision-eval/evaluate.py prompt_sha() from the same text block. If the vehicle prompt
        // changes, this fails: start a new evaluation run (new --run name) and update the expected value.
        assertThat(VisionConfidenceCalibrator.VEHICLE_PROMPT_SHA).isEqualTo("501b3308884a64f8");
    }

    @Test
    @DisplayName("Maps raw confidence through the step table when model and thinking budget match")
    void appliesStepTable() throws Exception {
        VisionConfidenceCalibrator c = new VisionConfidenceCalibrator(calibration("gemini-2.5-flash", 0), "gemini-2.5-flash", 0);

        assertThat(c.isActive()).isTrue();
        assertThat(c.calibrate(new BigDecimal("0.30"))).isEqualByComparingTo("0.40");
        assertThat(c.calibrate(new BigDecimal("0.90"))).isEqualByComparingTo("0.62");
        assertThat(c.calibrate(new BigDecimal("0.92"))).isEqualByComparingTo("0.62");
        assertThat(c.calibrate(new BigDecimal("0.99"))).isEqualByComparingTo("0.71");
    }

    @Test
    @DisplayName("Does nothing when the calibration was measured for another model or thinking budget")
    void ignoresMismatchedCalibration() throws Exception {
        assertThat(new VisionConfidenceCalibrator(calibration("gemini-2.5-flash", 0), "gemini-2.5-pro", 0).isActive()).isFalse();
        VisionConfidenceCalibrator otherBudget =
                new VisionConfidenceCalibrator(calibration("gemini-2.5-flash", 0), "gemini-2.5-flash", 1024);
        assertThat(otherBudget.isActive()).isFalse();
        assertThat(otherBudget.calibrate(new BigDecimal("0.90"))).isEqualByComparingTo("0.90");
        assertThat(VisionConfidenceCalibrator.none().calibrate(new BigDecimal("0.90"))).isEqualByComparingTo("0.90");
    }

    private static JsonNode marginCalibration() throws Exception {
        return new ObjectMapper().readTree("""
                {"signal": "probabilityMargin", "model": "gemini-2.5-flash", "thinkingBudget": 0, "images": 25,
                 "dataset": "test",
                 "table": [{"from": 0.0, "to": 0.10, "calibrated": 0.64, "n": 9},
                           {"from": 0.55, "to": 0.65, "calibrated": 0.87, "n": 2},
                           {"from": 0.70, "to": 0.90, "calibrated": 0.96, "n": 14}]}
                """);
    }

    private static java.util.Map<String, BigDecimal> probs(String... pairs) {
        java.util.Map<String, BigDecimal> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put(pairs[i], new BigDecimal(pairs[i + 1]));
        return m;
    }

    @Test
    @DisplayName("Probability margin = top minus runner-up, rounded to 2 decimals, 0 without probabilities (matches calibrate.py)")
    void marginMatchesPythonDefinition() {
        assertThat(VisionConfidenceCalibrator.margin(probs("FRONT_BUMPER", "0.9", "SIDE_PANEL", "0.05", "UNKNOWN", "0.05")))
                .isEqualByComparingTo("0.85");
        assertThat(VisionConfidenceCalibrator.margin(probs("MULTIPLE", "0.35", "FRONT_BUMPER", "0.30")))
                .isEqualByComparingTo("0.05");
        assertThat(VisionConfidenceCalibrator.margin(probs("WINDSHIELD", "0.95"))).isEqualByComparingTo("0.95");
        assertThat(VisionConfidenceCalibrator.margin(java.util.Map.of())).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("A margin-keyed map scores a specific answer high and a hedged MULTIPLE answer low, whatever Gemini's own confidence")
    void marginSignalSeparatesSpecificFromHedged() throws Exception {
        VisionConfidenceCalibrator c = new VisionConfidenceCalibrator(marginCalibration(), "gemini-2.5-flash", 0);
        BigDecimal geminiSays = new BigDecimal("0.90");

        assertThat(c.calibrate(geminiSays, probs("FRONT_BUMPER", "0.90", "SIDE_PANEL", "0.05"))).isEqualByComparingTo("0.96");
        assertThat(c.calibrate(geminiSays, probs("MULTIPLE", "0.35", "FRONT_BUMPER", "0.30"))).isEqualByComparingTo("0.64");
        assertThat(c.calibrate(geminiSays, probs("REAR_BUMPER", "0.70", "MULTIPLE", "0.10"))).isEqualByComparingTo("0.87");
        // No probabilities at all is treated as maximally hedged.
        assertThat(c.calibrate(geminiSays, java.util.Map.of())).isEqualByComparingTo("0.64");
    }

    @Test
    @DisplayName("GeminiVisionProvider applies a margin-keyed map using the probabilities in Gemini's answer")
    void providerUsesMarginSignal() throws Exception {
        RestTemplate rest = mock(RestTemplate.class);
        when(rest.postForEntity(anyString(), any(HttpEntity.class), eq(String.class))).thenReturn(ResponseEntity.ok("""
                {"candidates": [{"content": {"parts": [{"text": "{\\"damageType\\": \\"MULTIPLE\\", \\"severity\\": \\"MODERATE\\", \\"estimatedDamage\\": 3500, \\"confidence\\": 0.9, \\"photoQuality\\": \\"CLEAR\\", \\"damageTypeProbabilities\\": {\\"MULTIPLE\\": 0.35, \\"FRONT_BUMPER\\": 0.30, \\"SIDE_PANEL\\": 0.35}}"}]}}]}
                """));
        GeminiVisionProvider provider = new GeminiVisionProvider(rest, new ObjectMapper(),
                new EvidenceImageLoader(Set.of(), h -> null, u -> null),
                new VisionConfidenceCalibrator(marginCalibration(), "gemini-2.5-flash", 0));
        ReflectionTestUtils.setField(provider, "apiKey", "test-key");
        ReflectionTestUtils.setField(provider, "model", "gemini-2.5-flash");

        DamageAssessment result = provider.assess(new FNOLPayload("POL-AUTO-112233", LocalDate.now(), "CA", "dent",
                null, null, null, List.of("data:image/png;base64,iVBORw0KGgo="), null));

        // Gemini said 0.90, but a tie between areas is a hedged answer: below the 0.85 STP threshold.
        assertThat(result.rawConfidence()).isEqualByComparingTo("0.9");
        assertThat(result.confidence()).isEqualByComparingTo("0.64");
    }

    @Test
    @DisplayName("GeminiVisionProvider reports the calibrated confidence and keeps the model's own value")
    void providerUsesCalibratedConfidence() throws Exception {
        RestTemplate rest = mock(RestTemplate.class);
        when(rest.postForEntity(anyString(), any(HttpEntity.class), eq(String.class))).thenReturn(ResponseEntity.ok("""
                {"candidates": [{"content": {"parts": [{"text": "{\\"damageType\\": \\"FRONT_BUMPER\\", \\"severity\\": \\"MODERATE\\", \\"estimatedDamage\\": 3500, \\"confidence\\": 0.9, \\"photoQuality\\": \\"CLEAR\\"}"}]}}]}
                """));
        EvidenceImageLoader loader = new EvidenceImageLoader(Set.of(), h -> null, u -> null);
        GeminiVisionProvider provider = new GeminiVisionProvider(rest, new ObjectMapper(), loader,
                new VisionConfidenceCalibrator(calibration("gemini-2.5-flash", 0), "gemini-2.5-flash", 0));
        ReflectionTestUtils.setField(provider, "apiKey", "test-key");
        ReflectionTestUtils.setField(provider, "model", "gemini-2.5-flash");

        DamageAssessment result = provider.assess(new FNOLPayload("POL-AUTO-112233", LocalDate.now(), "CA", "dent",
                null, null, null, List.of("data:image/png;base64,iVBORw0KGgo="), null));

        assertThat(result.confidence()).isEqualByComparingTo("0.62");
        assertThat(result.rawConfidence()).isEqualByComparingTo("0.9");
    }
}
