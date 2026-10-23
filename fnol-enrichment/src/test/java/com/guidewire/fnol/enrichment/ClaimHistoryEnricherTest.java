package com.guidewire.fnol.enrichment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guidewire.fnol.common.Models.ClaimHistory;
import com.guidewire.fnol.common.Models.PolicyHistoryContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@ExtendWith(MockitoExtension.class)
class ClaimHistoryEnricherTest {

    @Mock
    private PolicyCenterClient policyClient;

    private ClaimHistoryEnricher enricher;

    @BeforeEach
    void setup() {
        enricher = new ClaimHistoryEnricher(policyClient);
    }

    @Test
    @DisplayName("Policy with 0 claims is LOW risk")
    void policyWith0ClaimsIsLowRisk() {
        when(policyClient.getPolicyClaimHistory("POL-AUTO-001")).thenReturn(new ArrayList<>());

        PolicyHistoryContext result = enricher.enrich("POL-AUTO-001");

        assertThat(result.claimFrequencyRisk()).isEqualTo("LOW");
        assertThat(result.totalPriorClaims()).isZero();
        assertThat(result.claimsLast6Months()).isZero();
        assertThat(result.claimsLast12Months()).isZero();
    }

    @Test
    @DisplayName("One claim 6-12 months ago is MEDIUM risk")
    void policyWith1ClaimIn12MonthsIsMediumRisk() {
        when(policyClient.getPolicyClaimHistory("POL-AUTO-002")).thenReturn(List.of(
                new ClaimHistory("CLM-001", LocalDate.now().minusDays(200), "CLOSED", new BigDecimal("1800"), "Theft")
        ));

        PolicyHistoryContext result = enricher.enrich("POL-AUTO-002");

        assertThat(result.claimFrequencyRisk()).isEqualTo("MEDIUM");
        assertThat(result.totalPriorClaims()).isEqualTo(1);
        assertThat(result.claimsLast6Months()).isZero();
        assertThat(result.claimsLast12Months()).isEqualTo(1);
    }

    @Test
    @DisplayName("Two claims in 12 months is HIGH risk")
    void policyWith2ClaimsIn12MonthsIsHighRisk() {
        when(policyClient.getPolicyClaimHistory("POL-AUTO-003")).thenReturn(List.of(
                new ClaimHistory("CLM-001", LocalDate.now().minusDays(45), "CLOSED", new BigDecimal("2500"), "Collision"),
                new ClaimHistory("CLM-002", LocalDate.now().minusDays(200), "CLOSED", new BigDecimal("1800"), "Theft")
        ));

        PolicyHistoryContext result = enricher.enrich("POL-AUTO-003");

        assertThat(result.claimFrequencyRisk()).isEqualTo("HIGH");
        assertThat(result.totalPriorClaims()).isEqualTo(2);
        assertThat(result.claimsLast6Months()).isEqualTo(1);
        assertThat(result.claimsLast12Months()).isEqualTo(2);
    }

    @Test
    @DisplayName("One claim in the last 6 months is HIGH risk")
    void policyWithClaimInLast6MonthsIsHighRisk() {
        when(policyClient.getPolicyClaimHistory("POL-AUTO-004")).thenReturn(List.of(
                new ClaimHistory("CLM-001", LocalDate.now().minusDays(45), "CLOSED", new BigDecimal("1500"), "Collision")
        ));

        PolicyHistoryContext result = enricher.enrich("POL-AUTO-004");

        assertThat(result.claimFrequencyRisk()).isEqualTo("HIGH");
        assertThat(result.claimsLast6Months()).isEqualTo(1);
    }

    @Test
    @DisplayName("Open claims are counted")
    void openClaimsAreCountedCorrectly() {
        when(policyClient.getPolicyClaimHistory("POL-AUTO-005")).thenReturn(List.of(
                new ClaimHistory("CLM-001", LocalDate.now().minusDays(10), "OPEN", new BigDecimal("5000"), "Collision"),
                new ClaimHistory("CLM-002", LocalDate.now().minusDays(30), "CLOSED", new BigDecimal("2000"), "Theft"),
                new ClaimHistory("CLM-003", LocalDate.now().minusDays(60), "OPEN", new BigDecimal("3000"), "Collision")
        ));

        PolicyHistoryContext result = enricher.enrich("POL-AUTO-005");

        assertThat(result.openClaimsCount()).isEqualTo(2);
        assertThat(result.totalPriorClaims()).isEqualTo(3);
    }

    @Test
    @DisplayName("Most recent claim details are captured")
    void mostRecentClaimDetailsAreCaptured() {
        LocalDate recentDate = LocalDate.now().minusDays(10);
        when(policyClient.getPolicyClaimHistory("POL-AUTO-006")).thenReturn(List.of(
                new ClaimHistory("CLM-001", recentDate, "OPEN", new BigDecimal("5000"), "Collision"),
                new ClaimHistory("CLM-002", LocalDate.now().minusDays(200), "CLOSED", new BigDecimal("2000"), "Theft")
        ));

        PolicyHistoryContext result = enricher.enrich("POL-AUTO-006");

        assertThat(result.lastClaimDate()).isEqualTo(recentDate);
        assertThat(result.lastClaimType()).isEqualTo("OPEN");
        assertThat(result.lastClaimAmount()).isEqualByComparingTo("5000");
    }

    @Test
    @DisplayName("D1: history is loaded over HTTP from /history, so POL-AUTO-445566 is HIGH and POL-AUTO-112233 is LOW")
    void enricherLoadsHistoryOverHttp() throws Exception {
        RestTemplate rest = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(rest);
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();

        server.expect(requestTo("http://localhost:9090/pc/policies/POL-AUTO-445566/history"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(json.writeValueAsString(List.of(
                        new ClaimHistory("HIST-5566-01", LocalDate.now().minusDays(40), "CLOSED", new BigDecimal("2200"), "Rear bumper"),
                        new ClaimHistory("HIST-5566-02", LocalDate.now().minusDays(130), "OPEN", new BigDecimal("3100"), "Side panel")
                )), MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://localhost:9090/pc/policies/POL-AUTO-112233/history"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(json.writeValueAsString(List.of(
                        new ClaimHistory("HIST-2233-01", LocalDate.now().minusYears(2), "CLOSED", new BigDecimal("1850"), "Glass")
                )), MediaType.APPLICATION_JSON));

        ClaimHistoryEnricher httpEnricher =
                new ClaimHistoryEnricher(new MockPolicyCenterClient(rest, "http://localhost:9090"));

        assertThat(httpEnricher.enrich("POL-AUTO-445566").claimFrequencyRisk()).isEqualTo("HIGH");
        assertThat(httpEnricher.enrich("POL-AUTO-112233").claimFrequencyRisk()).isEqualTo("LOW");
        server.verify();
    }
}
