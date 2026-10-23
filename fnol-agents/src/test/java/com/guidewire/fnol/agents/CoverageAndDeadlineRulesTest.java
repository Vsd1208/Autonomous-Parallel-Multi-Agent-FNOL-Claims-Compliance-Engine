package com.guidewire.fnol.agents;

import com.guidewire.fnol.common.Models.*;
import com.guidewire.fnol.enrichment.PolicyCenterClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** P9: coverage by line of business (D7) and acknowledge/decision deadlines with an urgency flag. */
class CoverageAndDeadlineRulesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 23);
    private static final Clock CLOCK = Clock.fixed(TODAY.atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC);

    private static PolicyCenterClient policyCenter(Policy policy, List<Coverage> coverages) {
        return new PolicyCenterClient() {
            @Override public Policy getPolicy(String n) { return policy; }
            @Override public List<Coverage> getCoverages(String n) { return coverages; }
            @Override public List<ClaimHistory> getPolicyClaimHistory(String n) { return List.of(); }
        };
    }

    private static Policy policy(String type, String status, LocalDate start, LocalDate end) {
        return new Policy("POL-" + type + "-334455", type, status, start, end, "Riley Smith", "TX", "Risk", List.of());
    }

    private static PolicyValidationResult validate(Policy policy, List<Coverage> coverages) {
        return new PolicyValidatorAgent(policyCenter(policy, coverages), new SyntheticCoverageRuleProvider(), CLOCK)
                .execute(policy.policyNumber());
    }

    @Test
    @DisplayName("D7: a home policy with DWELLING coverage passes, and the matched rule is in the reasoning")
    void homePolicyPassesWithDwelling() {
        PolicyValidationResult result = validate(
                policy("HOME", "ACTIVE", TODAY.minusMonths(6), TODAY.plusMonths(6)),
                List.of(new Coverage("DWELLING", new BigDecimal("350000"), new BigDecimal("2500"))));

        assertThat(result.covered()).isTrue();
        assertThat(result.coverageType()).isEqualTo("DWELLING");
        assertThat(result.reasoning()).anyMatch(r -> r.contains("HOME requires DWELLING or PERSONAL_PROPERTY"));
    }

    @Test
    @DisplayName("A home policy with only PERSONAL_PROPERTY coverage also passes")
    void homePolicyPassesWithPersonalProperty() {
        PolicyValidationResult result = validate(
                policy("HOME", "ACTIVE", TODAY.minusMonths(6), TODAY.plusMonths(6)),
                List.of(new Coverage("PERSONAL_PROPERTY", new BigDecimal("100000"), new BigDecimal("1000"))));

        assertThat(result.covered()).isTrue();
        assertThat(result.coverageType()).isEqualTo("PERSONAL_PROPERTY");
    }

    @Test
    @DisplayName("An auto policy with only COMPREHENSIVE coverage passes")
    void autoPolicyPassesWithComprehensive() {
        PolicyValidationResult result = validate(
                policy("AUTO", "ACTIVE", TODAY.minusMonths(6), TODAY.plusMonths(6)),
                List.of(new Coverage("COMPREHENSIVE", new BigDecimal("40000"), new BigDecimal("500"))));

        assertThat(result.covered()).isTrue();
        assertThat(result.coverageType()).isEqualTo("COMPREHENSIVE");
    }

    @Test
    @DisplayName("An expired policy fails even with matching coverage")
    void expiredPolicyFails() {
        PolicyValidationResult result = validate(
                policy("AUTO", "ACTIVE", TODAY.minusYears(2), TODAY.minusYears(1)),
                List.of(new Coverage("COLLISION", new BigDecimal("50000"), new BigDecimal("1000"))));

        assertThat(result.policyActive()).isFalse();
        assertThat(result.covered()).isFalse();
    }

    @Test
    @DisplayName("A policy that is not yet in force fails")
    void futurePolicyFails() {
        PolicyValidationResult result = validate(
                policy("AUTO", "ACTIVE", TODAY.plusDays(5), TODAY.plusYears(1)),
                List.of(new Coverage("COLLISION", new BigDecimal("50000"), new BigDecimal("1000"))));

        assertThat(result.covered()).isFalse();
    }

    @Test
    @DisplayName("An unknown line of business has no qualifying coverage")
    void unknownLineOfBusinessFails() {
        PolicyValidationResult result = validate(
                policy("MARINE", "ACTIVE", TODAY.minusMonths(6), TODAY.plusMonths(6)),
                List.of(new Coverage("HULL", new BigDecimal("50000"), new BigDecimal("1000"))));

        assertThat(result.covered()).isFalse();
    }

    @Test
    @DisplayName("Deadlines: acknowledgeBy equals the legacy deadline field, decisionBy comes later, both are labelled placeholders")
    void deadlineCarriesAcknowledgeAndDecision() {
        DeadlineResult ca = new SyntheticDeadlineRuleProvider(CLOCK).deadline("CA", TODAY.minusDays(2));

        assertThat(ca.acknowledgeBy()).isEqualTo(TODAY.minusDays(2).plusDays(30));
        assertThat(ca.deadline()).isEqualTo(ca.acknowledgeBy());
        assertThat(ca.decisionBy()).isAfter(ca.acknowledgeBy());
        assertThat(ca.disclaimer()).contains("PROTOTYPE PLACEHOLDER");
    }

    @Test
    @DisplayName("Urgency flag changes with the incident date: OK, WARNING within 3 days, CRITICAL when past due")
    void urgencyFlagFollowsIncidentDate() {
        SyntheticDeadlineRuleProvider provider = new SyntheticDeadlineRuleProvider(CLOCK);

        // TX acknowledge window is 15 days.
        assertThat(provider.deadline("TX", TODAY.minusDays(2)).urgencyFlag()).isEqualTo("OK");       // due in 13 days
        assertThat(provider.deadline("TX", TODAY.minusDays(11)).urgencyFlag()).isEqualTo("OK");      // due in 4 days
        assertThat(provider.deadline("TX", TODAY.minusDays(12)).urgencyFlag()).isEqualTo("WARNING"); // due in 3 days
        assertThat(provider.deadline("TX", TODAY.minusDays(15)).urgencyFlag()).isEqualTo("WARNING"); // due today
        assertThat(provider.deadline("TX", TODAY.minusDays(16)).urgencyFlag()).isEqualTo("CRITICAL");// 1 day late
    }
}
