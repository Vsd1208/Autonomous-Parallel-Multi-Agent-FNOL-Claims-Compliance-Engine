package com.guidewire.fnol.agents;

import com.guidewire.fnol.common.Models.Coverage;
import com.guidewire.fnol.common.Models.Endorsement;
import com.guidewire.fnol.common.Models.Policy;
import com.guidewire.fnol.common.Models.PolicyValidationResult;
import com.guidewire.fnol.enrichment.PolicyCenterClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Coverage is judged at the date of loss. The clock says February 2026 throughout, and must not matter. */
class PolicyAtDateOfLossTest {

    private static final LocalDate FROM = LocalDate.of(2025, 1, 15);
    private static final LocalDate TO = LocalDate.of(2026, 1, 15);
    private static final List<Coverage> COLLISION = List.of(new Coverage("COLLISION", new BigDecimal("50000"), new BigDecimal("1000")));
    private static final Clock FEBRUARY_2026 = Clock.fixed(LocalDate.of(2026, 2, 20).atStartOfDay(ZoneId.of("UTC")).toInstant(), ZoneId.of("UTC"));

    private static PolicyCenterClient center(Policy policy, List<Coverage> coverages) {
        return new PolicyCenterClient() {
            @Override
            public Policy getPolicy(String number) {
                return policy;
            }

            @Override
            public List<Coverage> getCoverages(String number) {
                return coverages;
            }

            @Override
            public List<com.guidewire.fnol.common.Models.ClaimHistory> getPolicyClaimHistory(String number) {
                return List.of();
            }
        };
    }

    private static Policy policy(String status, LocalDate from, LocalDate to, LocalDate cancelled, List<Endorsement> endorsements) {
        return new Policy("POL-AUTO-1", "AUTO", status, from, to, "Insured", "CA", "risk", List.of(), "US", "USD", cancelled, endorsements);
    }

    private static PolicyValidationResult at(Policy policy, List<Coverage> coverages, LocalDate loss) {
        return new PolicyValidatorAgent(center(policy, coverages), new SyntheticCoverageRuleProvider(), FEBRUARY_2026).execute("POL-AUTO-1", loss);
    }

    private static Policy plain() {
        return policy("ACTIVE", FROM, TO, null, null);
    }

    @Test
    @DisplayName("Reported after the policy ended, for a loss in term: covered. This is the case that used to be refused")
    void reportedAfterExpiry() {
        PolicyValidationResult r = at(policy("EXPIRED", FROM, TO, null, null), COLLISION, LocalDate.of(2025, 12, 10));
        assertThat(r.covered()).isTrue();
        assertThat(r.coverageState()).isEqualTo(PolicyValidationResult.COVERED);
    }

    @Test
    @DisplayName("Active at the date of loss")
    void activeAtLoss() {
        assertThat(at(plain(), COLLISION, LocalDate.of(2025, 6, 1)).covered()).isTrue();
    }

    @Test
    @DisplayName("A loss before the policy began or after it ended is not in force, even if the policy is active today")
    void outsideTerm() {
        assertThat(at(plain(), COLLISION, FROM.minusDays(1)).coverageState()).isEqualTo(PolicyValidationResult.NOT_IN_FORCE);
        assertThat(at(plain(), COLLISION, TO.plusDays(1)).coverageState()).isEqualTo(PolicyValidationResult.NOT_IN_FORCE);
        Policy activeToday = policy("ACTIVE", LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1), null, null);
        assertThat(at(activeToday, COLLISION, LocalDate.of(2025, 12, 1)).covered()).isFalse();
    }

    @Test
    @DisplayName("Cancelled before the loss: not in force. Cancelled after the loss: covered")
    void cancellation() {
        LocalDate cancelled = LocalDate.of(2025, 8, 1);
        assertThat(at(policy("CANCELLED", FROM, TO, cancelled, null), COLLISION, LocalDate.of(2025, 9, 1)).coverageState()).isEqualTo(PolicyValidationResult.NOT_IN_FORCE);
        assertThat(at(policy("CANCELLED", FROM, TO, cancelled, null), COLLISION, LocalDate.of(2025, 7, 1)).covered()).isTrue();
    }

    @Test
    @DisplayName("An endorsement counts from its effective date: before the loss it applies, after the loss it does not")
    void endorsements() {
        Endorsement addsFrom = new Endorsement(LocalDate.of(2025, 6, 1), Endorsement.ADD, "COLLISION", new BigDecimal("80000"), new BigDecimal("500"));
        PolicyValidationResult after = at(policy("ACTIVE", FROM, TO, null, List.of(addsFrom)), List.of(), LocalDate.of(2025, 7, 1));
        assertThat(after.covered()).isTrue();
        assertThat(after.coverageLimit()).isEqualByComparingTo("80000");

        PolicyValidationResult before = at(policy("ACTIVE", FROM, TO, null, List.of(addsFrom)), List.of(), LocalDate.of(2025, 5, 1));
        assertThat(before.coverageState()).isEqualTo(PolicyValidationResult.NOT_COVERED);

        Endorsement removes = new Endorsement(LocalDate.of(2025, 6, 1), Endorsement.REMOVE, "COLLISION", null, null);
        assertThat(at(policy("ACTIVE", FROM, TO, null, List.of(removes)), COLLISION, LocalDate.of(2025, 7, 1)).coverageState()).isEqualTo(PolicyValidationResult.NOT_COVERED);
        assertThat(at(policy("ACTIVE", FROM, TO, null, List.of(removes)), COLLISION, LocalDate.of(2025, 5, 1)).covered()).isTrue();
    }

    @Test
    @DisplayName("Missing dates give UNVERIFIED, not a guess")
    void missingDates() {
        assertThat(at(policy("ACTIVE", null, TO, null, null), COLLISION, LocalDate.of(2025, 6, 1)).coverageState()).isEqualTo(PolicyValidationResult.UNVERIFIED);
        assertThat(at(policy("ACTIVE", FROM, null, null, null), COLLISION, LocalDate.of(2025, 6, 1)).coverageState()).isEqualTo(PolicyValidationResult.UNVERIFIED);
        assertThat(at(plain(), COLLISION, null).coverageState()).isEqualTo(PolicyValidationResult.UNVERIFIED);
        assertThat(at(policy("CANCELLED", FROM, TO, null, null), COLLISION, LocalDate.of(2025, 6, 1)).coverageState()).isEqualTo(PolicyValidationResult.UNVERIFIED);
        assertThat(at(policy("ACTIVE", FROM, TO, null, List.of(new Endorsement(null, Endorsement.ADD, "COLLISION", BigDecimal.ONE, BigDecimal.ONE))), List.of(), LocalDate.of(2025, 6, 1))
                .reasoning()).anyMatch(r -> r.contains("no effective date"));
    }

    @Test
    @DisplayName("Boundaries: the first day is covered; the last day and the cancellation day go to a person, since the time of day is unknown")
    void boundaries() {
        assertThat(at(plain(), COLLISION, FROM).covered()).isTrue();
        assertThat(at(plain(), COLLISION, TO).coverageState()).isEqualTo(PolicyValidationResult.UNVERIFIED);
        LocalDate cancelled = LocalDate.of(2025, 8, 1);
        assertThat(at(policy("CANCELLED", FROM, TO, cancelled, null), COLLISION, cancelled).coverageState()).isEqualTo(PolicyValidationResult.UNVERIFIED);
    }

    @Test
    @DisplayName("The server's clock and time zone play no part: the same loss gets the same answer whatever today is")
    void clockDoesNotMatter() {
        Clock later = Clock.fixed(LocalDate.of(2031, 1, 1).atStartOfDay(ZoneId.of("Asia/Kolkata")).toInstant(), ZoneId.of("Asia/Kolkata"));
        PolicyValidationResult r = new PolicyValidatorAgent(center(plain(), COLLISION), new SyntheticCoverageRuleProvider(), later).execute("POL-AUTO-1", LocalDate.of(2025, 6, 1));
        assertThat(r.covered()).isTrue();
    }

    @Test
    @DisplayName("In force but with no matching coverage: status and coverage are separate answers")
    void statusIsNotCoverage() {
        PolicyValidationResult r = at(plain(), List.of(new Coverage("LIABILITY", BigDecimal.TEN, BigDecimal.ONE)), LocalDate.of(2025, 6, 1));
        assertThat(r.policyActive()).isTrue();
        assertThat(r.covered()).isFalse();
        assertThat(r.coverageState()).isEqualTo(PolicyValidationResult.NOT_COVERED);
    }
}
