package com.guidewire.fnol.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guidewire.fnol.api.persistence.ClaimRepository;
import com.guidewire.fnol.common.Models.*;
import com.guidewire.fnol.enrichment.ClaimCenterClient;
import com.guidewire.fnol.enrichment.PolicyCenterClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The full AI analysis is kept on the server for adjusters on any browser, without personal text. */
@SpringBootTest
@AutoConfigureMockMvc
class AnalysisPersistenceTest {

    private static final String SSN = "321-54-9876";
    private static final String DESCRIPTION = "Avery Johnson SSN " + SSN + " was hit on the driver side at a junction.";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ClaimRepository claims;
    @MockBean private PolicyCenterClient policyCenterClient;
    @MockBean private ClaimCenterClient claimCenterClient;
    @Value("${fnol.security.adjuster-token}") private String token;

    @Test
    @DisplayName("GET /analysis returns the branch results to adjusters; the stored copy has no description, SSN or audit records")
    void analysisIsStoredWithoutPersonalText() throws Exception {
        LocalDate start = LocalDate.now().minusMonths(6);
        when(policyCenterClient.getPolicy("POL-AUTO-112233")).thenReturn(new Policy("POL-AUTO-112233", "AUTO", "ACTIVE",
                start, start.plusYears(1), "Avery Johnson", "CA", "2025 Camry", List.of()));
        when(policyCenterClient.getCoverages("POL-AUTO-112233")).thenReturn(List.of(
                new Coverage("COLLISION", new BigDecimal("50000"), new BigDecimal("1000"))));
        when(claimCenterClient.createClaim(any())).thenReturn(new ClaimCreateResponse("CLM-2026-000731", "DRAFT_PENDING_REVIEW"));

        FNOLPayload payload = new FNOLPayload("POL-AUTO-112233", LocalDate.now().minusDays(4), "CA", DESCRIPTION,
                "Avery Johnson", "01/02/1980", SSN, List.of("mock://photo/normal.jpg"), "WEB", null, null,
                new BigDecimal("3000"));
        mockMvc.perform(post("/api/v1/fnol/intake").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload))).andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/fnol/CLM-2026-000731/analysis")).andExpect(status().isUnauthorized());
        String body = mockMvc.perform(get("/api/v1/fnol/CLM-2026-000731/analysis").header("X-Adjuster-Token", token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode analysis = objectMapper.readTree(body);
        assertThat(analysis.path("branchA").path("damageAssessment").path("damageType").asText()).isEqualTo("FRONT_BUMPER");
        assertThat(analysis.path("branchA").path("reserve").path("claimedAmount").decimalValue()).isEqualByComparingTo("3000");
        assertThat(analysis.path("branchB").path("pii").path("piiDetected").asBoolean()).isTrue();
        assertThat(analysis.path("branchB").path("pii").path("redactedDescription").isNull()).isTrue();
        assertThat(analysis.path("branchB").path("audit")).isEmpty();
        assertThat(analysis.path("notes")).isNotEmpty();

        String stored = claims.findById("CLM-2026-000731").orElseThrow().analysisJson;
        assertThat(stored).doesNotContain(SSN).doesNotContain("driver side at a junction").doesNotContain("01/02/1980");

        // The claim row's own JSON does not carry the analysis blob.
        mockMvc.perform(get("/api/v1/fnol/status/CLM-2026-000731"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analysisJson").doesNotExist());
    }
}
