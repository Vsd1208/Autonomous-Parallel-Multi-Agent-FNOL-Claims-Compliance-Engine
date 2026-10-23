package com.guidewire.fnol.orchestrator;

import com.guidewire.fnol.common.Models.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static com.guidewire.fnol.orchestrator.IntakeReviewService.*;
import static org.assertj.core.api.Assertions.assertThat;

/** P10: Human Review Point 1 intake flags. */
class IntakeReviewServiceTest {

    private static final LocalDate START = LocalDate.now().minusMonths(6);
    private static final Policy POLICY = new Policy("POL-AUTO-112233", "AUTO", "ACTIVE", START, START.plusYears(1),
            "Avery Johnson", "CA", "Camry", List.of());
    private static final PolicyHistoryContext LOW = new PolicyHistoryContext(0, 0, 0, null, null, null, 0, "LOW");

    private final IntakeReviewService service = new IntakeReviewService();

    private static FNOLPayload payload(LocalDate incident, String claimant, List<String> evidence) {
        return new FNOLPayload("POL-AUTO-112233", incident, "CA", "Scraped a pole.", claimant,
                "01/02/1980", "123-45-6789", evidence, "WEB");
    }

    private static FNOLPayload clean() {
        return payload(LocalDate.now().minusDays(3), "Avery Johnson", List.of("mock://photo/normal.jpg"));
    }

    @Test
    @DisplayName("A clean payload raises no flags")
    void cleanPayloadHasNoFlags() {
        assertThat(service.review(clean(), POLICY, LOW)).isEmpty();
    }

    @Test
    @DisplayName("Claimant name comparison ignores case and extra spaces")
    void nameComparisonIsLenient() {
        FNOLPayload p = payload(LocalDate.now().minusDays(3), "  avery   JOHNSON ", List.of("mock://x"));
        assertThat(service.review(p, POLICY, LOW)).isEmpty();
    }

    @Test
    @DisplayName("MISSING_EVIDENCE when no evidence URIs are sent")
    void missingEvidence() {
        assertThat(service.review(payload(LocalDate.now().minusDays(3), "Avery Johnson", List.of()), POLICY, LOW))
                .containsExactly(MISSING_EVIDENCE);
        assertThat(service.review(payload(LocalDate.now().minusDays(3), "Avery Johnson", null), POLICY, LOW))
                .containsExactly(MISSING_EVIDENCE);
    }

    @Test
    @DisplayName("CLAIMANT_NAME_MISMATCH when the claimant is not the insured")
    void claimantNameMismatch() {
        FNOLPayload p = payload(LocalDate.now().minusDays(3), "Demo User", List.of("mock://x"));
        assertThat(service.review(p, POLICY, LOW)).containsExactly(CLAIMANT_NAME_MISMATCH);
    }

    @Test
    @DisplayName("INCIDENT_OUTSIDE_POLICY_PERIOD when the incident predates the policy term")
    void incidentOutsidePolicyPeriod() {
        FNOLPayload p = payload(START.minusDays(1), "Avery Johnson", List.of("mock://x"));
        assertThat(service.review(p, POLICY, LOW)).containsExactly(INCIDENT_OUTSIDE_POLICY_PERIOD);
    }

    @Test
    @DisplayName("INCIDENT_NEAR_POLICY_START when the incident is within 30 days of the policy start")
    void incidentNearPolicyStart() {
        assertThat(service.review(payload(START.plusDays(29), "Avery Johnson", List.of("mock://x")), POLICY, LOW))
                .containsExactly(INCIDENT_NEAR_POLICY_START);
        assertThat(service.review(payload(START.plusDays(30), "Avery Johnson", List.of("mock://x")), POLICY, LOW))
                .isEmpty();
    }

    @Test
    @DisplayName("HIGH_CLAIM_FREQUENCY when the history risk is HIGH")
    void highClaimFrequency() {
        PolicyHistoryContext high = new PolicyHistoryContext(2, 1, 2, null, null, null, 1, "HIGH");
        assertThat(service.review(clean(), POLICY, high)).containsExactly(HIGH_CLAIM_FREQUENCY);
    }
}
