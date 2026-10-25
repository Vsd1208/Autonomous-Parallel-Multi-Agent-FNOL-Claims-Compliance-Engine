package com.guidewire.fnol.agents;

import com.guidewire.fnol.common.Models.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Diagram: reserve estimation "provide confidence score"; subrogation "assess liability, generate compliance notes". */
class ReserveAndSubrogationTest {

    private static FNOLPayload payload(String description) {
        return new FNOLPayload("POL-AUTO-112233", LocalDate.now().minusDays(3), "CA", description,
                null, null, null, List.of("mock://photo/normal.jpg"), null);
    }

    private static DamageAssessment damage(String severity, String confidence, String quality) {
        return new DamageAssessment("FRONT_BUMPER", severity, new BigDecimal("4200"), new BigDecimal(confidence),
                quality, Map.of());
    }

    @Test
    @DisplayName("Reserve confidence is the vision confidence weighted by photo quality")
    void reserveConfidenceFollowsPhotoQuality() {
        ReserveCalculatorAgent agent = new ReserveCalculatorAgent();

        ReserveResult clear = agent.execute(damage("MODERATE", "0.91", "CLEAR"));
        assertThat(clear.recommendedReserve()).isEqualByComparingTo("4200");
        assertThat(clear.confidence()).isEqualByComparingTo("0.91");
        assertThat(clear.confidenceBasis()).contains("CLEAR");

        assertThat(agent.execute(damage("MODERATE", "0.90", "BLURRY")).confidence()).isEqualByComparingTo("0.63");
        assertThat(agent.execute(damage("MODERATE", "0.60", "UNUSABLE")).confidence()).isEqualByComparingTo("0.30");
        assertThat(agent.execute(damage("MODERATE", "0.90", "UNKNOWN")).confidence()).isEqualByComparingTo("0.72");
    }

    private static PolicyValidationResult coverage(String limit, String deductible) {
        return new PolicyValidationResult("POL-AUTO-112233", true, true, true, true, "COLLISION",
                new BigDecimal(limit), new BigDecimal(deductible), List.of());
    }

    @Test
    @DisplayName("Claimed amount below the AI estimate: pay the claimed amount less the deductible")
    void claimedBelowEstimateUsesClaimedAmount() {
        ReserveResult r = new ReserveCalculatorAgent().calculate(damage("MODERATE", "0.91", "CLEAR"),
                coverage("50000", "1000"), new BigDecimal("3000"));

        assertThat(r.settlementBasis()).isEqualByComparingTo("3000");
        assertThat(r.deductible()).isEqualByComparingTo("1000");
        assertThat(r.recommendedReserve()).isEqualByComparingTo("2000");
    }

    @Test
    @DisplayName("Claimed amount above the AI estimate: reserve only what the photos support, less the deductible")
    void claimedAboveEstimateUsesAiEstimate() {
        ReserveResult r = new ReserveCalculatorAgent().calculate(damage("MODERATE", "0.91", "CLEAR"),
                coverage("50000", "1000"), new BigDecimal("9000"));

        assertThat(r.claimedAmount()).isEqualByComparingTo("9000");
        assertThat(r.settlementBasis()).isEqualByComparingTo("4200");
        assertThat(r.recommendedReserve()).isEqualByComparingTo("3200");
    }

    @Test
    @DisplayName("The reserve is never negative (loss below the deductible) and never above the coverage limit")
    void reserveIsBoundedByDeductibleAndLimit() {
        ReserveCalculatorAgent agent = new ReserveCalculatorAgent();

        assertThat(agent.calculate(damage("MINOR", "0.91", "CLEAR"), coverage("50000", "1000"), new BigDecimal("800"))
                .recommendedReserve()).isEqualByComparingTo("0");
        DamageAssessment total = new DamageAssessment("SIDE_PANEL", "SEVERE", new BigDecimal("80000"),
                new BigDecimal("0.9"), "CLEAR", Map.of());
        assertThat(agent.calculate(total, coverage("50000", "1000"), null).recommendedReserve())
                .isEqualByComparingTo("50000");
    }

    @Test
    @DisplayName("Branch B liability assessment: text factors only, PENDING_SEVERITY, with compliance notes")
    void liabilityAssessmentHasComplianceNotes() {
        SubrogationScorerAgent agent = new SubrogationScorerAgent(new DeterministicSubrogationScorer());

        SubrogationResult thirdParty = agent.assessLiability(payload("Rear-ended by other driver. Police report filed."));
        assertThat(thirdParty.score()).isEqualTo(55);
        assertThat(thirdParty.recommendedAction()).isEqualTo("PENDING_SEVERITY");
        assertThat(thirdParty.factors()).containsOnlyKeys("thirdPartyMention", "policeReportSignal");
        assertThat(thirdParty.complianceNotes())
                .anyMatch(n -> n.contains("other party's insurer"))
                .anyMatch(n -> n.contains("report number"));

        SubrogationResult noPolice = agent.assessLiability(payload("Rear-ended at a light."));
        assertThat(noPolice.complianceNotes()).anyMatch(n -> n.contains("request one"));

        SubrogationResult alone = agent.assessLiability(payload("Scraped a pole in the garage."));
        assertThat(alone.complianceNotes()).anyMatch(n -> n.contains("subrogation recovery is unlikely"));
    }

    @Test
    @DisplayName("The gate adds the severity points, decides the action and adds the referral note")
    void finalizeAddsSeverityAndReferral() {
        SubrogationScorerAgent agent = new SubrogationScorerAgent(new DeterministicSubrogationScorer());
        SubrogationResult liability = agent.assessLiability(payload("Rear-ended by other driver. Police report filed."));

        SubrogationResult moderate = agent.finalizeScore(liability, damage("MODERATE", "0.91", "CLEAR"));
        assertThat(moderate.score()).isEqualTo(70);
        assertThat(moderate.recommendedAction()).isEqualTo("INVESTIGATE");
        assertThat(moderate.complianceNotes()).last().asString().contains("refer to the subrogation unit");
        assertThat(moderate.complianceNotes()).hasSize(liability.complianceNotes().size() + 1);

        SubrogationResult minor = agent.finalizeScore(liability, damage("MINOR", "0.91", "CLEAR"));
        assertThat(minor.score()).isEqualTo(60);
        assertThat(minor.recommendedAction()).isEqualTo("MONITOR");
    }
}
