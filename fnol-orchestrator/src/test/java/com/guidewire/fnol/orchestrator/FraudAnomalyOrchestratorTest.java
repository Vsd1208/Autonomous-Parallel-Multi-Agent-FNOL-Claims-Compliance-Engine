package com.guidewire.fnol.orchestrator;

import com.guidewire.fnol.common.Models.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class FraudAnomalyOrchestratorTest {

    private OrchestratorFixture fixture;
    private FNOLOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        fixture = new OrchestratorFixture();
        orchestrator = fixture.build();
        orchestrator.setFraudScorer(new FraudAnomalyScorer(fixture.stp));
    }

    /** A claim that passes every rule, reported late, early in the policy, slightly above the estimate. */
    private static FNOLPayload unusualButRuleClean() {
        FNOLPayload p = OrchestratorFixture.payload("POL-AUTO-112233", "Avery Johnson", "Scraped a pole while parking.",
                "4600");
        return new FNOLPayload(p.policyNumber(), LocalDate.now().minusDays(60), p.state(), p.description(),
                p.claimantName(), p.claimantDob(), p.claimantSsn(), p.evidenceUris(), p.channel(), null, null,
                p.claimedAmount(), p.evidenceAngles());
    }

    @Test
    @DisplayName("An ordinary claim gets a low anomaly score, no flag, and still waits for advisory review")
    void ordinaryClaimIsNotFlagged() {
        FNOLResponse r = orchestrator.process(OrchestratorFixture.payload("POL-AUTO-112233", "Avery Johnson",
                "Minor fender bender in parking lot"));

        assertThat(r.fraudAnomaly()).isNotNull();
        assertThat(r.fraudAnomaly().anomalous()).isFalse();
        assertThat(r.gateFlags()).doesNotContain(FraudAnomalyScorer.FRAUD_ANOMALY);
        assertThat(r.status()).isEqualTo("PENDING_ADVISORY_REVIEW");
    }

    @Test
    @DisplayName("FRAUD_ANOMALY alone sends an otherwise straight-through claim to an adjuster, explained and audited")
    void anomalyRoutesToAdjusterWithReasons() {
        FNOLResponse r = orchestrator.process(unusualButRuleClean());

        assertThat(r.inputFlags()).isEmpty();
        assertThat(r.gateFlags()).containsExactly(FraudAnomalyScorer.FRAUD_ANOMALY);
        assertThat(r.status()).isEqualTo("HUMAN_REVIEW");
        assertThat(r.fraudAnomaly().score()).isGreaterThanOrEqualTo(r.fraudAnomaly().threshold());
        assertThat(r.fraudAnomaly().topContributors()).extracting(FraudContribution::feature)
                .contains("reportLagDays", "policyAgeDays");
        assertThat(r.notes()).anyMatch(n -> n.contains("FRAUD_ANOMALY") && n.contains("special investigations unit")
                && n.contains("never denies"));
        assertThat(r.branchB().audit()).extracting(AuditRecord::agentName).contains("FraudAnomalyScorer");
        assertThat(r.branchB().audit()).filteredOn(a -> a.agentName().equals("FraudAnomalyScorer"))
                .extracting(AuditRecord::status).containsExactly("FLAGGED");
        // An adjuster activity in ClaimCenter carries the reason
        assertThat(fixture.claimCenter.activities.get(r.claimId()))
                .anyMatch(a -> a.subject().toLowerCase().contains("fraud anomaly"));
    }

    @Test
    @DisplayName("The flag never denies: the reserve and coverage are unchanged by the anomaly score")
    void anomalyDoesNotChangeTheMoney() {
        FNOLResponse flagged = orchestrator.process(unusualButRuleClean());

        assertThat(flagged.branchA().coverage().covered()).isTrue();
        assertThat(flagged.branchA().reserve().recommendedReserve()).isEqualByComparingTo(new BigDecimal("3200"));
        assertThat(flagged.status()).isNotIn("REJECTED_BY_ADJUSTER", "BLOCKED_COMPLIANCE");
    }

    @Test
    @DisplayName("Turned off, no score is computed and the claim follows the existing rules")
    void disabled() {
        fixture.stp.setFraudAnomalyEnabled(false);
        FNOLResponse r = orchestrator.process(unusualButRuleClean());

        assertThat(r.fraudAnomaly()).isNull();
        assertThat(r.gateFlags()).isEmpty();
        assertThat(r.status()).isEqualTo("PENDING_ADVISORY_REVIEW");
    }
}
