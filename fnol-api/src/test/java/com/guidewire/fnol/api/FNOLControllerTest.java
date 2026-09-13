package com.guidewire.fnol.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guidewire.fnol.common.Models.*;
import com.guidewire.fnol.enrichment.ClaimCenterClient;
import com.guidewire.fnol.enrichment.PolicyCenterClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class FNOLControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private PolicyCenterClient policyCenterClient;

    @MockBean
    private ClaimCenterClient claimCenterClient;

    @Test
    @DisplayName("GET /api/v1/health returns 200 UP status")
    void healthEndpoint_returnsUp() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("POST /api/v1/fnol/intake processes valid FNOL and returns explainable response")
    void intakeEndpoint_processesValidFNOL() throws Exception {
        Policy mockPolicy = new Policy(
                "POL-AUTO-112233", "AUTO", "ACTIVE",
                LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1),
                "Avery Johnson", "CA", "2025 Camry", List.of()
        );
        List<Coverage> mockCoverages = List.of(
                new Coverage("COLLISION", new BigDecimal("50000"), new BigDecimal("1000"))
        );

        when(policyCenterClient.getPolicy(eq("POL-AUTO-112233"))).thenReturn(mockPolicy);
        when(policyCenterClient.getCoverages(eq("POL-AUTO-112233"))).thenReturn(mockCoverages);
        when(claimCenterClient.createClaim(any())).thenReturn(
                new ClaimCreateResponse("CLM-2026-000001", "OPEN")
        );

        FNOLPayload payload = new FNOLPayload(
                "POL-AUTO-112233",
                LocalDate.now().minusDays(1),
                "CA",
                "Rear-ended by another driver. Police report filed. Avery Johnson SSN 123-45-6789.",
                "Avery Johnson",
                "01/02/1980",
                "123-45-6789",
                List.of("mock://photo/evidence.jpg")
        );

        mockMvc.perform(post("/api/v1/fnol/intake")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.claimId").value("CLM-2026-000001"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.branchA.coverage.covered").value(true))
                .andExpect(jsonPath("$.branchA.reserve.recommendedReserve").value(4830))
                .andExpect(jsonPath("$.branchB.pii.piiDetected").value(true))
                .andExpect(jsonPath("$.branchB.deadline.state").value("CA"))
                .andExpect(jsonPath("$.branchB.subrogation.score").isNumber())
                .andExpect(jsonPath("$.branchB.audit").isArray())
                .andExpect(jsonPath("$.explanation").exists())
                .andExpect(jsonPath("$.explanation.decision").exists())
                .andExpect(jsonPath("$.explanation.summary").exists())
                .andExpect(jsonPath("$.explanation.factors").isArray())
                .andExpect(jsonPath("$.explanation.factors.length()").value(6))
                .andExpect(jsonPath("$.explanation.recommendedAction").exists())
                .andExpect(jsonPath("$.policyContext").exists())
                .andExpect(jsonPath("$.policyContext.claimFrequencyRisk").exists());

        // Check claim status retrieval
        mockMvc.perform(get("/api/v1/fnol/status/CLM-2026-000001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.claimId").value("CLM-2026-000001"))
                .andExpect(jsonPath("$.policyNumber").value("POL-AUTO-112233"));
    }

    @Test
    @DisplayName("POST /api/v1/fnol/intake returns 400 for invalid payload format")
    void intakeEndpoint_returns400OnInvalidPayload() throws Exception {
        FNOLPayload invalidPayload = new FNOLPayload(
                "", // Blank policy number
                LocalDate.now().plusDays(5), // Future date
                "ZZ", // Invalid state
                "", // Blank description
                null, null, null, List.of()
        );

        mockMvc.perform(post("/api/v1/fnol/intake")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidPayload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("GET /api/v1/fnol/status/{claimId} returns 404 for non-existent claim")
    void statusEndpoint_returns404ForUnknownClaim() throws Exception {
        mockMvc.perform(get("/api/v1/fnol/status/CLM-NONEXISTENT-999"))
                .andExpect(status().isNotFound());
    }
}
