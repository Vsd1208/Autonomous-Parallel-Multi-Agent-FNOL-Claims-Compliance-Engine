package com.guidewire.fnol.enrichment;

import com.guidewire.fnol.common.Models.ClaimHistory;
import com.guidewire.fnol.common.Models.PolicyHistoryContext;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class ClaimHistoryEnricherTest {

    @Mock
    private PolicyCenterClient policyClient;

    private ClaimHistoryEnricher enricher;

    @Before
    public void setup() {
        enricher = new ClaimHistoryEnricher(policyClient);
    }

    /**
     * Test 1: Policy with 0 claims should be LOW risk
     */
    @Test
    public void testPolicyWith0ClaimsIsLowRisk() {
        String policyNumber = "POL-AUTO-001";

        when(policyClient.getPolicyClaimHistory(policyNumber))
                .thenReturn(new ArrayList<>());

        PolicyHistoryContext result = enricher.enrich(policyNumber);

        assertEquals("LOW", result.claimFrequencyRisk());
        assertEquals(0, result.totalPriorClaims());
        assertEquals(0, result.claimsLast6Months());
        assertEquals(0, result.claimsLast12Months());
    }

    /**
     * Test 2: Policy with 1 claim in last 12 months AND 0 in last 6 months = MEDIUM risk
     */
    @Test
    public void testPolicyWith1ClaimIn12MonthsIsMediumRisk() {
        String policyNumber = "POL-AUTO-002";

        List<ClaimHistory> history = List.of(
                new ClaimHistory(
                        "CLM-001",
                        LocalDate.now().minusDays(200),
                        "CLOSED",
                        new BigDecimal("1800"),
                        "Theft"
                )
        );
        when(policyClient.getPolicyClaimHistory(policyNumber))
                .thenReturn(history);

        PolicyHistoryContext result = enricher.enrich(policyNumber);

        assertEquals("MEDIUM", result.claimFrequencyRisk());
        assertEquals(1, result.totalPriorClaims());
        assertEquals(0, result.claimsLast6Months());
        assertEquals(1, result.claimsLast12Months());
    }

    /**
     * Test 3: Policy with 2 claims in 12 months = HIGH risk
     */
    @Test
    public void testPolicyWith2ClaimsIn12MonthsIsHighRisk() {
        String policyNumber = "POL-AUTO-003";

        List<ClaimHistory> history = List.of(
                new ClaimHistory(
                        "CLM-001",
                        LocalDate.now().minusDays(45),
                        "CLOSED",
                        new BigDecimal("2500"),
                        "Collision"
                ),
                new ClaimHistory(
                        "CLM-002",
                        LocalDate.now().minusDays(200),
                        "CLOSED",
                        new BigDecimal("1800"),
                        "Theft"
                )
        );
        when(policyClient.getPolicyClaimHistory(policyNumber))
                .thenReturn(history);

        PolicyHistoryContext result = enricher.enrich(policyNumber);

        assertEquals("HIGH", result.claimFrequencyRisk());
        assertEquals(2, result.totalPriorClaims());
        assertEquals(1, result.claimsLast6Months());
        assertEquals(2, result.claimsLast12Months());
    }

    /**
     * Test 4: Policy with 1 claim in last 6 months = HIGH risk
     */
    @Test
    public void testPolicyWithClaimInLast6MonthsIsHighRisk() {
        String policyNumber = "POL-AUTO-004";

        List<ClaimHistory> history = List.of(
                new ClaimHistory(
                        "CLM-001",
                        LocalDate.now().minusDays(45),
                        "CLOSED",
                        new BigDecimal("1500"),
                        "Collision"
                )
        );
    }

    /**
     * Test 5: Verify open claims are counted
     */
    @Test
    public void testOpenClaimsAreCountedCorrectly() {
        String policyNumber = "POL-AUTO-005";

        List<ClaimHistory> history = List.of(
                new ClaimHistory("CLM-001", LocalDate.now().minusDays(10), "OPEN", new BigDecimal("5000"), "Collision"),
                new ClaimHistory("CLM-002", LocalDate.now().minusDays(30), "CLOSED", new BigDecimal("2000"), "Theft"),
                new ClaimHistory("CLM-003", LocalDate.now().minusDays(60), "OPEN", new BigDecimal("3000"), "Collision")
        );
        when(policyClient.getPolicyClaimHistory(policyNumber))
                .thenReturn(history);

        PolicyHistoryContext result = enricher.enrich(policyNumber);

        assertEquals(2, result.openClaimsCount());
        assertEquals(3, result.totalPriorClaims());
    }

    /**
     * Test 6: Verify most recent claim details are captured
     */
    @Test
    public void testMostRecentClaimDetailsAreCaptured() {
        String policyNumber = "POL-AUTO-006";

        LocalDate recentDate = LocalDate.now().minusDays(10);

        List<ClaimHistory> history = List.of(
                new ClaimHistory("CLM-001", recentDate, "OPEN", new BigDecimal("5000"), "Collision"),
                new ClaimHistory("CLM-002", LocalDate.now().minusDays(200), "CLOSED", new BigDecimal("2000"), "Theft")
        );
        when(policyClient.getPolicyClaimHistory(policyNumber))
                .thenReturn(history);

        PolicyHistoryContext result = enricher.enrich(policyNumber);

        assertEquals(recentDate, result.lastClaimDate());
        assertEquals("OPEN", result.lastClaimType());
        assertEquals(new BigDecimal("5000"), result.lastClaimAmount());
    }
}