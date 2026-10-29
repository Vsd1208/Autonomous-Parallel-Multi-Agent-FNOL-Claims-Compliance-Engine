package com.guidewire.fnol.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Feedback loop and governance metrics: each adjuster decision is kept next to what the AI said. */
@SpringBootTest
@AutoConfigureMockMvc
class AiGovernanceTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockBean private PolicyCenterClient policyCenterClient;
    @MockBean private ClaimCenterClient claimCenterClient;
    @Value("${fnol.security.adjuster-token}") private String token;

    @Test
    @DisplayName("A decision with a confirmed damage area is recorded next to the AI's view and counted in the metrics")
    void decisionsFeedTheMetrics() throws Exception {
        LocalDate start = LocalDate.now().minusMonths(6);
        when(policyCenterClient.getPolicy("POL-AUTO-112233")).thenReturn(new Policy("POL-AUTO-112233", "AUTO", "ACTIVE",
                start, start.plusYears(1), "Avery Johnson", "CA", "2025 Camry", List.of()));
        when(policyCenterClient.getCoverages("POL-AUTO-112233")).thenReturn(List.of(
                new Coverage("COLLISION", new BigDecimal("50000"), new BigDecimal("1000"))));
        when(claimCenterClient.createClaim(any())).thenReturn(new ClaimCreateResponse("CLM-2026-000814", "DRAFT_PENDING_REVIEW"));
        FNOLPayload payload = new FNOLPayload("POL-AUTO-112233", LocalDate.now().minusDays(41), "CA",
                "Reversed into a bollard.", "Avery Johnson", null, null, List.of("mock://photo/normal.jpg"), "WEB", null,
                null, new BigDecimal("9000"), List.of("CLOSE_UP"));
        mockMvc.perform(post("/api/v1/fnol/intake").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload))).andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/fnol/CLM-2026-000814/review").header("X-Adjuster-Token", token)
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(Map.of(
                        "decision", "APPROVED_BY_ADJUSTER", "adjusterId", "ADJ-007", "adjustedReserve", 2500,
                        "overrideReason", "Repair quote", "confirmedDamageType", "front_bumper"))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/ai/metrics")).andExpect(status().isUnauthorized());
        JsonNode feedback = objectMapper.readTree(mockMvc.perform(get("/api/v1/ai/feedback").header("X-Adjuster-Token", token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode row = null;
        for (JsonNode f : feedback) if ("CLM-2026-000814".equals(f.path("claimId").asText())) row = f;
        assertThat(row).isNotNull();
        assertThat(row.path("aiDamageType").asText()).isEqualTo("FRONT_BUMPER");
        assertThat(row.path("adjusterDamageType").asText()).isEqualTo("FRONT_BUMPER");
        assertThat(row.path("adjusterReserve").decimalValue()).isEqualByComparingTo("2500");
        assertThat(row.path("aiStatus").asText()).isEqualTo("HUMAN_REVIEW");
        assertThat(row.path("probabilityMargin").decimalValue()).isEqualByComparingTo("0.85");

        JsonNode metrics = objectMapper.readTree(mockMvc.perform(get("/api/v1/ai/metrics").header("X-Adjuster-Token", token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(metrics.path("agreement").path("damageTypeAgreed").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(metrics.path("agreement").path("reserveChanged").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(metrics.path("leakage").path("amountNotReserved").decimalValue()).isGreaterThanOrEqualTo(new BigDecimal("4800"));
        assertThat(metrics.path("flags").path("CLAIMED_EXCEEDS_AI_ESTIMATE").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(metrics.path("drift")).isNotEmpty();
    }
}
