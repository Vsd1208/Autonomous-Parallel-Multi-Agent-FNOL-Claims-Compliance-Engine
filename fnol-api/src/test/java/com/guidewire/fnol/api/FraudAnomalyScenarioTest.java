package com.guidewire.fnol.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.guidewire.fnol.common.Models.*;
import com.guidewire.fnol.enrichment.ClaimCenterClient;
import com.guidewire.fnol.enrichment.PolicyCenterClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Replays demo scenarios 1 and 10 (demo-scenarios/*.json) through the whole application with the same policy the mock
 * PolicyCenter seeds (term started six months ago, no prior claims) and the deterministic mock vision provider, at the
 * dates run-all.sh uses. Checks that the fraud anomaly score is wired in and that the scenarios' expectations hold.
 */
@SpringBootTest
@AutoConfigureMockMvc
class FraudAnomalyScenarioTest {

    private static final AtomicInteger IDS = new AtomicInteger();
    /** The seeded policy's shape under its own number, so other tests' claims in the shared database never collide. */
    private static final String POLICY = "POL-AUTO-990010";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockBean private PolicyCenterClient policyCenter;
    @MockBean private ClaimCenterClient claimCenter;

    @BeforeEach
    void seed() {
        when(policyCenter.getPolicy(eq(POLICY))).thenReturn(new Policy(POLICY, "AUTO", "ACTIVE",
                LocalDate.now().minusMonths(6), LocalDate.now().minusMonths(6).plusYears(1), "Avery Johnson", "CA",
                "Synthetic vehicle", List.of()));
        when(policyCenter.getCoverages(eq(POLICY))).thenReturn(List.of(
                new Coverage("COLLISION", new BigDecimal("50000"), new BigDecimal("1000"))));
        when(policyCenter.getPolicyClaimHistory(eq(POLICY))).thenReturn(List.of());
        when(claimCenter.createClaim(any())).thenAnswer(inv ->
                new ClaimCreateResponse("CLM-FA-%06d".formatted(IDS.incrementAndGet()), "DRAFT_PENDING_REVIEW"));
    }

    private JsonNode run(String scenarioFile, int daysAgo) throws Exception {
        JsonNode scenario = objectMapper.readTree(Files.readString(Path.of("..", "demo-scenarios", scenarioFile)));
        ObjectNode payload = (ObjectNode) scenario.path("payload").deepCopy();
        payload.put("incidentDate", LocalDate.now().minusDays(daysAgo).toString());
        payload.put("policyNumber", POLICY);
        String body = mockMvc.perform(post("/api/v1/fnol/intake")
                        .header("X-Adjuster-Token", "test-adjuster-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode response = objectMapper.readTree(body);
        assertThat(response.path("status").asText()).isEqualTo(scenario.path("expectedClaimStatus").asText());
        assertThat(response.path("explanation").path("decision").asText())
                .isEqualTo(scenario.path("expectedDecision").asText());
        return response;
    }

    @Test
    @DisplayName("Scenario 1 at the latest date run-all.sh uses (28 days) is not flagged and stays straight-through")
    void cleanScenarioIsNotFlagged() throws Exception {
        for (int daysAgo : new int[] {1, 28}) {
            JsonNode r = run("scenario_1_clean_stp.json", daysAgo);
            assertThat(r.path("gateFlags").toString()).doesNotContain("FRAUD_ANOMALY");
            assertThat(r.path("fraudAnomaly").path("anomalous").asBoolean()).isFalse();
        }
    }

    @Test
    @DisplayName("Scenario 10 across its date range raises FRAUD_ANOMALY with its top contributors")
    void fraudScenarioIsFlagged() throws Exception {
        for (int daysAgo : new int[] {45, 75}) {
            JsonNode r = run("scenario_10_fraud_anomaly.json", daysAgo);
            assertThat(r.path("inputFlags")).isEmpty();
            assertThat(r.path("gateFlags").toString()).contains("FRAUD_ANOMALY");
            assertThat(r.path("fraudAnomaly").path("anomalous").asBoolean()).isTrue();
            assertThat(r.path("fraudAnomaly").path("topContributors").toString())
                    .contains("reportLagDays", "policyAgeDays");
        }
    }
}
