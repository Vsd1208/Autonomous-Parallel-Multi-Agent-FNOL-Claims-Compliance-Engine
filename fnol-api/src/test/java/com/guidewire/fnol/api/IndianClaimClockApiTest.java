package com.guidewire.fnol.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guidewire.fnol.common.Models.ClaimCreateResponse;
import com.guidewire.fnol.common.Models.Coverage;
import com.guidewire.fnol.common.Models.Policy;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** An Indian claim all the way through intake, with the switch on. */
@SpringBootTest(properties = "fnol.claim-clock.india.enabled=true")
@AutoConfigureMockMvc
class IndianClaimClockApiTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @MockBean private PolicyCenterClient policyCenter;
    @MockBean private ClaimCenterClient claimCenter;

    @Test
    @DisplayName("Through intake: the milestones and citations, no rupee reserve invented, a person sets it, and health says the clock is on")
    void indianClaim() throws Exception {
        LocalDate start = LocalDate.now().minusMonths(6);
        when(policyCenter.getPolicy("POL-AUTO-778899")).thenReturn(new Policy("POL-AUTO-778899", "AUTO", "ACTIVE", start, start.plusYears(1),
                "Asha Kulkarni", "MH", "synthetic", List.of(new Coverage("COLLISION", new BigDecimal("800000"), new BigDecimal("2500"))), "IN", "INR"));
        when(policyCenter.getCoverages("POL-AUTO-778899")).thenReturn(List.of(new Coverage("COLLISION", new BigDecimal("800000"), new BigDecimal("2500"))));
        when(claimCenter.createClaim(any())).thenReturn(new ClaimCreateResponse("CLM-2026-0N0001", "OPEN"));
        Map<String, Object> payload = Map.of("policyNumber", "POL-AUTO-778899", "incidentDate", LocalDate.now().minusDays(3).toString(), "state", "MH",
                "description", "Another car hit my front bumper.", "claimantName", "Asha Kulkarni", "channel", "API", "claimedAmount", 75000,
                "evidenceUris", List.of("mock://photo/normal-front.jpg", "mock://photo/normal-rear.jpg", "mock://photo/normal-left.jpg", "mock://photo/normal-right.jpg", "mock://photo/normal-close-up.jpg"),
                "evidenceAngles", List.of("FRONT", "REAR", "LEFT", "RIGHT", "CLOSE_UP"));

        JsonNode r = mapper.readTree(mockMvc.perform(post("/api/v1/fnol/intake").header("X-Adjuster-Token", "test-adjuster-token")
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(payload)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        JsonNode clock = r.path("branchB").path("deadline");
        assertThat(clock.path("milestones").size()).isEqualTo(3);
        assertThat(clock.path("milestones").path(0).path("dueBy").asText()).isEqualTo(LocalDate.now().plusDays(1).toString());
        assertThat(clock.path("decisionBy").asText()).isEqualTo(LocalDate.now().plusDays(16).toString());
        assertThat(r.path("gateFlags").toString()).contains("RESERVE_TABLE_ENTRY_MISSING");
        assertThat(r.path("status").asText()).isEqualTo("HUMAN_REVIEW");
        assertThat(r.path("notes").toString()).contains("CURRENCY: this policy is in INR").contains("IRDAI/PP&GR/CIR/MISC/117/9/2024");
        mockMvc.perform(get("/api/v1/health")).andExpect(jsonPath("$.indiaClock").value("ON"));
    }
}
