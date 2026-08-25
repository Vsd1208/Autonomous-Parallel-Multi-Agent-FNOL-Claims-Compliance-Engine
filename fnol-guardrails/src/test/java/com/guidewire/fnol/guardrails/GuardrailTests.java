package com.guidewire.fnol.guardrails;

import com.guidewire.fnol.common.Models.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GuardrailTests {

    private InputGuardrail inputGuardrail;
    private OutputGuardrail outputGuardrail;
    private PIIGuardrail piiGuardrail;
    private ValidationGuardrail validationGuardrail;

    @BeforeEach
    void setUp() {
        inputGuardrail = new InputGuardrail();
        outputGuardrail = new OutputGuardrail();
        piiGuardrail = new PIIGuardrail();
        validationGuardrail = new ValidationGuardrail();
    }

    private FNOLPayload validPayload() {
        return new FNOLPayload(
                "POL-AUTO-112233",
                LocalDate.now().minusDays(2),
                "CA",
                "Clean description with no raw PII",
                "Avery Johnson",
                "01/02/1980",
                "123-45-6789",
                List.of("mock://photo/1.jpg")
        );
    }

    @Test
    void inputGuardrail_validPayloadPasses() {
        assertThatCode(() -> inputGuardrail.validate(validPayload()))
                .doesNotThrowAnyException();
    }

    @Test
    void inputGuardrail_invalidPolicyFormatFails() {
        FNOLPayload bad = new FNOLPayload(
                "INVALID-POLICY",
                LocalDate.now().minusDays(1),
                "CA",
                "Valid description",
                null, null, null, List.of()
        );
        assertThatThrownBy(() -> inputGuardrail.validate(bad))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Policy number must match");
    }

    @Test
    void inputGuardrail_futureIncidentDateFails() {
        FNOLPayload bad = new FNOLPayload(
                "POL-AUTO-112233",
                LocalDate.now().plusDays(1),
                "CA",
                "Valid description",
                null, null, null, List.of()
        );
        assertThatThrownBy(() -> inputGuardrail.validate(bad))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Incident date is required and cannot be in the future");
    }

    @Test
    void inputGuardrail_unsupportedStateFails() {
        FNOLPayload bad = new FNOLPayload(
                "POL-AUTO-112233",
                LocalDate.now().minusDays(1),
                "ZZ",
                "Valid description",
                null, null, null, List.of()
        );
        assertThatThrownBy(() -> inputGuardrail.validate(bad))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("State must be one of CA, NY, TX, FL, IL");
    }

    @Test
    void inputGuardrail_blankDescriptionFails() {
        FNOLPayload bad = new FNOLPayload(
                "POL-AUTO-112233",
                LocalDate.now().minusDays(1),
                "CA",
                "   ",
                null, null, null, List.of()
        );
        assertThatThrownBy(() -> inputGuardrail.validate(bad))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Description is required");
    }

    @Test
    void outputGuardrail_validReserveAndSubrogationPasses() {
        ReserveResult reserve = new ReserveResult(new BigDecimal("4200"), new BigDecimal("1.15"), new BigDecimal("4830"));
        SubrogationResult subrogation = new SubrogationResult(78, "INVESTIGATE", java.util.Map.of());
        assertThatCode(() -> outputGuardrail.validate(reserve, subrogation))
                .doesNotThrowAnyException();
    }

    @Test
    void outputGuardrail_negativeReserveFails() {
        ReserveResult reserve = new ReserveResult(new BigDecimal("-100"), new BigDecimal("1.15"), new BigDecimal("-115"));
        SubrogationResult subrogation = new SubrogationResult(50, "MONITOR", java.util.Map.of());
        assertThatThrownBy(() -> outputGuardrail.validate(reserve, subrogation))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Reserve cannot be negative");
    }

    @Test
    void outputGuardrail_subrogationOutOfRangeFails() {
        ReserveResult reserve = new ReserveResult(new BigDecimal("1000"), new BigDecimal("1.15"), new BigDecimal("1150"));
        SubrogationResult subrogation = new SubrogationResult(105, "INVESTIGATE", java.util.Map.of());
        assertThatThrownBy(() -> outputGuardrail.validate(reserve, subrogation))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Subrogation score outside 0-100");
    }

    @Test
    void piiGuardrail_cleanRedactedPasses() {
        PIIResult clean = new PIIResult("Claimant reported accident. SSN: ***-**-****.", List.of("SSN"), true);
        assertThatCode(() -> piiGuardrail.validate(clean))
                .doesNotThrowAnyException();
    }

    @Test
    void piiGuardrail_unredactedSsnFails() {
        PIIResult unredacted = new PIIResult("Claimant SSN is 123-45-6789.", List.of(), false);
        assertThatThrownBy(() -> piiGuardrail.validate(unredacted))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PII redaction failed");
    }

    @Test
    void validationGuardrail_coveredPolicyPasses() {
        PolicyValidationResult valid = new PolicyValidationResult(
                "POL-AUTO-112233", true, true, true, true,
                "COLLISION", new BigDecimal("50000"), new BigDecimal("1000"), List.of("All checks passed")
        );
        assertThatCode(() -> validationGuardrail.requireCovered(valid))
                .doesNotThrowAnyException();
    }

    @Test
    void validationGuardrail_uncoveredPolicyFails() {
        PolicyValidationResult invalid = new PolicyValidationResult(
                "POL-AUTO-112233", true, true, false, false,
                null, BigDecimal.ZERO, BigDecimal.ZERO, List.of("No collision coverage")
        );
        assertThatThrownBy(() -> validationGuardrail.requireCovered(invalid))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Policy is not eligible");
    }
}
