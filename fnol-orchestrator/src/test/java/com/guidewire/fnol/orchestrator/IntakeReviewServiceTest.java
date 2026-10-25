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

    /** Any evidence given is sent as the standard set (one copy per angle), so only the flag under test fires. */
    private static FNOLPayload payload(LocalDate incident, String claimant, List<String> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return new FNOLPayload("POL-AUTO-112233", incident, "CA", "Scraped a pole.", claimant,
                    "01/02/1980", "123-45-6789", evidence, "WEB");
        }
        return withAngles(incident, claimant, AUTO_PHOTO_SET.stream().map(a -> evidence.get(0)).toList(), AUTO_PHOTO_SET);
    }

    private static FNOLPayload withAngles(LocalDate incident, String claimant, List<String> evidence, List<String> angles) {
        return new FNOLPayload("POL-AUTO-112233", incident, "CA", "Scraped a pole.", claimant,
                "01/02/1980", "123-45-6789", evidence, "WEB", null, null, null, angles);
    }

    @Test
    @DisplayName("INCOMPLETE_PHOTO_SET when an auto claim lacks a side of the vehicle or the close-up")
    void incompletePhotoSet() {
        LocalDate d = LocalDate.now().minusDays(3);
        assertThat(service.review(withAngles(d, "Avery Johnson", List.of("mock://a"), null), POLICY, LOW))
                .containsExactly(INCOMPLETE_PHOTO_SET);
        FNOLPayload noRear = withAngles(d, "Avery Johnson", List.of("mock://a", "mock://b", "mock://c", "mock://d"),
                List.of("front", "LEFT", "RIGHT", "CLOSE_UP"));
        assertThat(service.review(noRear, POLICY, LOW)).containsExactly(INCOMPLETE_PHOTO_SET);
        assertThat(missingPhotoAngles(noRear, POLICY)).containsExactly("REAR");
    }

    @Test
    @DisplayName("A home claim needs an overview and a close-up")
    void homePhotoSet() {
        Policy home = new Policy("POL-HOME-334455", "HOME", "ACTIVE", START, START.plusYears(1),
                "Avery Johnson", "TX", "House", List.of());
        LocalDate d = LocalDate.now().minusDays(3);
        assertThat(service.review(withAngles(d, "Avery Johnson", List.of("mock://a", "mock://b"),
                List.of("OVERVIEW", "CLOSE_UP")), home, LOW)).isEmpty();
        assertThat(missingPhotoAngles(withAngles(d, "Avery Johnson", List.of("mock://a"), List.of("CLOSE_UP")), home))
                .containsExactly("OVERVIEW");
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
