package com.guidewire.fnol.agents;

import com.guidewire.fnol.common.Models.FNOLPayload;
import com.guidewire.fnol.common.Models.PIIResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PiiDetectionTest {

    private final PiiRedactionAgent agent = new PiiRedactionAgent();

    private PIIResult scan(String description) {
        return agent.execute(new FNOLPayload("POL-AUTO-112233", LocalDate.now(), "CA", description, "Avery Johnson", "01/02/1980", null, List.of(), "WEB"));
    }

    @Test
    @DisplayName("No invented confidence: the result reports what was found and how, and a re-scan's outcome")
    void noArtificialConfidence() {
        PIIResult r = scan("Call me at 415-555-0100.");
        assertThat(r.redactionConfidence()).isNull();
        assertThat(r.validation()).isEqualTo(PIIResult.VALIDATED);
        assertThat(r.detections()).singleElement().satisfies(d -> {
            assertThat(d.category()).isEqualTo("PHONE");
            assertThat(d.detector()).isEqualTo("PATTERN");
            assertThat(d.count()).isEqualTo(1);
            assertThat(d.confidence()).isNull();
            assertThat(d.redacted()).isTrue();
        });
        assertThat(scan("A clean description.").validation()).isEqualTo(PIIResult.VALIDATED);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "My email is a.b+c@example.co.uk thanks | EMAIL | a.b+c@example.co.uk",
            "Call (415) 555-0100 please | PHONE | 555-0100",
            "Call +91 98765 43210? | PHONE | 98765",
            "Mobile 9876543210 now | PHONE | 9876543210",
            "I live at 42 Maple Grove Road near the park | POSTAL_ADDRESS | Maple Grove",
            "My policy POL-AUTO-112233 applies | POLICY_NUMBER | POL-AUTO-112233",
            "See claim CLM-2026-000123 too | CLAIM_NUMBER | CLM-2026-000123",
            "SSN 123-45-6789 on file | SSN | 123-45-6789",
            "Aadhaar 1234 5678 9012 given | GOVERNMENT_ID | 1234 5678 9012",
            "PAN ABCDE1234F given | GOVERNMENT_ID | ABCDE1234F",
            "Card 4111 1111 1111 1111 charged | FINANCIAL | 4111",
            "Paid from account no 001234567 today | FINANCIAL | 001234567",
    })
    @DisplayName("Each category is found and masked")
    void categories(String text, String category, String secret) {
        PIIResult r = scan(text);
        assertThat(r.detectedTypes()).contains(category);
        assertThat(r.redactedDescription()).doesNotContain(secret);
        assertThat(r.validation()).isEqualTo(PIIResult.VALIDATED);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Repair quote of $4,200 for a 2025 Camry on Route 66 near exit 12.",
            "I was doing 35 mph on I-95 at 5:30 pm when it happened.",
            "The bumper cost 1500 and the door 3200, 2 panels in total.",
            "Damage to the front bumper, about 40 percent of the panel.",
            "VIN 1HGCM82633A004352 is on the label.",
    })
    @DisplayName("Ordinary insurance details are left alone")
    void leavesInsuranceDetails(String text) {
        PIIResult r = scan(text);
        assertThat(r.piiDetected()).as(String.valueOf(r.detectedTypes())).isFalse();
        assertThat(r.redactedDescription()).isEqualTo(text);
    }

    @Test
    @DisplayName("Known values are matched exactly, in any case, including the surname alone, and not inside other words")
    void knownValues() {
        PIIResult r = scan("AVERY JOHNSON called, and Mr Johnson confirmed. Johnsonville is nearby.");
        assertThat(r.redactedDescription()).doesNotContain("AVERY JOHNSON").doesNotContain("Mr Johnson").contains("Johnsonville");
        assertThat(r.detections()).anyMatch(d -> d.category().equals("FULL_NAME") && d.detector().equals("KNOWN_VALUE") && d.count() == 2);
    }

    @Test
    @DisplayName("The validator fails closed if anything is left: a pattern, or a known value")
    void validatorFailsClosed() {
        assertThat(PiiRedactionAgent.validate("still 123-45-6789 here", List.of())).isFalse();
        assertThat(PiiRedactionAgent.validate("hello avery johnson", List.of("Avery Johnson"))).isFalse();
        assertThat(PiiRedactionAgent.validate("all clean", List.of("Avery Johnson"))).isTrue();
    }

    @Test
    @DisplayName("A name not on the claim is not found. This limit is real, and stated")
    void unknownNamesAreNotFound() {
        assertThat(scan("Bob Smith hit my car.").redactedDescription()).contains("Bob Smith");
    }

    @Test
    @DisplayName("Before any external text call: input with personal data never reaches the model, and the template is used")
    void failsClosedBeforeExternalModel() {
        boolean[] called = {false};
        DraftingModel model = (i, t, s) -> {
            called[0] = true;
            return DraftingModel.Turn.finish("Draft");
        };
        DraftingAgent.Draft d = new DraftingAgent(model, 6, 2).draft("rules", Map.of("claim_summary",
                new DraftingAgent.Tool("summary", "{\"contact\":\"avery@example.com\"}")), "TEMPLATE");
        assertThat(called[0]).isFalse();
        assertThat(d.fromModel()).isFalse();
        assertThat(d.text()).isEqualTo("TEMPLATE");
        assertThat(d.fallbackReason()).contains("personal data");
    }
}
