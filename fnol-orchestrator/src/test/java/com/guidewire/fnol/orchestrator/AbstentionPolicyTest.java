package com.guidewire.fnol.orchestrator;

import com.guidewire.fnol.agents.MockVisionProvider;
import com.guidewire.fnol.agents.VisionProvider;
import com.guidewire.fnol.common.Models.Abstention;
import com.guidewire.fnol.common.Models.DamageAssessment;
import com.guidewire.fnol.common.Models.FNOLPayload;
import com.guidewire.fnol.common.Models.FNOLResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AbstentionPolicyTest {

    private static final BigDecimal MIN = new BigDecimal("0.85");

    private static DamageAssessment assessment(String type, String quality, String confidence, String source) {
        return new DamageAssessment(type, "MINOR", new BigDecimal("1500"), new BigDecimal(confidence), quality,
                Map.of(type, BigDecimal.ONE), null, "VEHICLE", null, source);
    }

    private static Abstention evaluate(DamageAssessment d, String... flags) {
        return AbstentionPolicy.evaluate(d, List.of(flags), MIN, true);
    }

    @Test
    @DisplayName("A clear, confident, versioned answer is not an abstention")
    void clearAnswer() {
        Abstention a = evaluate(assessment("FRONT_BUMPER", "CLEAR", "0.90", "gemini prompt x thinking 0"));
        assertThat(a.abstained()).isFalse();
        assertThat(a.reasons()).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
            "BLURRY,      INSUFFICIENT_QUALITY",
            "UNUSABLE,    INSUFFICIENT_QUALITY",
    })
    @DisplayName("Photos too poor to read: abstain")
    void poorPhotos(String quality, String reason) {
        assertThat(evaluate(assessment("FRONT_BUMPER", quality, "0.90", "s")).reasons()).containsExactly(reason);
    }

    @Test
    @DisplayName("Each other reason is reported by name")
    void reasons() {
        DamageAssessment fine = assessment("FRONT_BUMPER", "CLEAR", "0.90", "s");
        assertThat(evaluate(fine, GateConsistencyChecker.MODEL_DISAGREEMENT).reasons()).containsExactly(AbstentionPolicy.MODEL_DISAGREEMENT);
        assertThat(evaluate(fine, GateConsistencyChecker.AMBIGUOUS_DAMAGE_TYPE).reasons()).containsExactly(AbstentionPolicy.LOW_CONFIDENCE);
        assertThat(evaluate(assessment("FRONT_BUMPER", "CLEAR", "0.84", "s")).reasons()).containsExactly(AbstentionPolicy.LOW_CONFIDENCE);
        assertThat(evaluate(fine, GateConsistencyChecker.PHOTO_SUBJECT_MISMATCH).reasons()).containsExactly(AbstentionPolicy.OUT_OF_DISTRIBUTION);
        assertThat(evaluate(fine, GateConsistencyChecker.DAMAGE_TYPE_LINE_MISMATCH).reasons()).containsExactly(AbstentionPolicy.UNSUPPORTED_DAMAGE);
        assertThat(evaluate(assessment("UNKNOWN", "CLEAR", "0.90", "s")).reasons()).containsExactly(AbstentionPolicy.UNSUPPORTED_DAMAGE);
        assertThat(evaluate(fine, GateConsistencyChecker.ON_PREMISE_VISION).reasons()).containsExactly(AbstentionPolicy.INFERENCE_FAILURE);
    }

    @Test
    @DisplayName("The model's failure placeholder: an inference failure with no model version, and its 0.60 is not read as an answer")
    void inferenceFailure() {
        assertThat(evaluate(assessment("UNKNOWN", "UNKNOWN", "0.60", null)).reasons()).contains(
                AbstentionPolicy.INFERENCE_FAILURE, AbstentionPolicy.MISSING_MODEL_VERSION);
    }

    @Test
    @DisplayName("An answer from a model other than the one asked for is an abstention")
    void substituteModel() {
        assertThat(evaluate(assessment("FRONT_BUMPER", "CLEAR", "0.90", "other-model instead of gemini-2.5-flash prompt x thinking 0")).reasons())
                .containsExactly(AbstentionPolicy.MODEL_VERSION_CHANGED);
    }

    @Test
    @DisplayName("A missing model version counts only when a real model is meant to be answering")
    void modelVersionRequiredOnlyWhenConfigured() {
        DamageAssessment noVersion = assessment("FRONT_BUMPER", "CLEAR", "0.90", null);
        assertThat(AbstentionPolicy.evaluate(noVersion, List.of(), MIN, true).reasons()).containsExactly(AbstentionPolicy.MISSING_MODEL_VERSION);
        assertThat(AbstentionPolicy.evaluate(noVersion, List.of(), MIN, false).abstained()).isFalse();
    }

    private static FNOLResponse processWith(VisionProvider vision, boolean requireVersion) {
        OrchestratorFixture fixture = new OrchestratorFixture();
        fixture.vision = vision;
        fixture.stp.setRequireModelVersion(requireVersion);
        return fixture.build().process(OrchestratorFixture.payload("POL-AUTO-112233", "Avery Johnson", "Scraped a pole in a parking garage."));
    }

    private static VisionProvider answering(DamageAssessment d) {
        return new VisionProvider() {
            @Override
            public DamageAssessment assess(FNOLPayload payload) {
                return d;
            }
        };
    }

    @Test
    @DisplayName("Abstaining sends the claim to a person. It is not a rejection: the claim stays open")
    void abstainedClaimGoesToAPersonAndIsNotRejected() {
        FNOLResponse r = processWith(answering(assessment("UNKNOWN", "UNUSABLE", "0.60", null)), false);
        assertThat(r.abstention().abstained()).isTrue();
        assertThat(r.abstention().reasons()).contains(AbstentionPolicy.INFERENCE_FAILURE, AbstentionPolicy.INSUFFICIENT_QUALITY);
        assertThat(r.status()).isEqualTo("HUMAN_REVIEW");
        assertThat(r.requiresHumanValidation()).isTrue();
        assertThat(r.explanation().decision()).doesNotContainIgnoringCase("DENY").doesNotContainIgnoringCase("REJECT");
        assertThat(r.notes()).anyMatch(n -> n.startsWith("AI ABSTAINED") && n.contains("not a finding against the claim"));
    }

    @Test
    @DisplayName("An answer with no model version cannot go straight through, even when every factor passes")
    void abstentionAloneBlocksStraightThrough() {
        // The mock's normal answer passes every factor and names no model.
        FNOLResponse without = processWith(new MockVisionProvider(), false);
        FNOLResponse with = processWith(new MockVisionProvider(), true);
        assertThat(without.status()).as("control: this answer otherwise qualifies").isEqualTo("PENDING_ADVISORY_REVIEW");
        assertThat(with.status()).isEqualTo("HUMAN_REVIEW");
        assertThat(with.gateFlags()).contains(AbstentionPolicy.AI_ABSTAINED);
        assertThat(with.abstention().reasons()).containsExactly(AbstentionPolicy.MISSING_MODEL_VERSION);
    }
}
