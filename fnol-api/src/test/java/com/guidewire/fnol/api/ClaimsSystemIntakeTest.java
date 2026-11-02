package com.guidewire.fnol.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guidewire.fnol.api.persistence.ClaimEntity;
import com.guidewire.fnol.common.Models.*;
import com.guidewire.fnol.enrichment.ClaimCenterClient;
import com.guidewire.fnol.enrichment.DuplicateLossException;
import com.guidewire.fnol.enrichment.PolicyCenterClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * With claims-system events configured, new losses are filed in the claims system first: the portal and the agent's
 * phone intake answer with the claims system's claim number at once, and nothing is analysed in the request (the
 * analysis follows the claim.submitted event, see {@link ClaimEventExtensionTest}).
 */
@SpringBootTest(properties = "fnol.claims-events.secret=test-event-secret")
@AutoConfigureMockMvc
class ClaimsSystemIntakeTest {

    private static final String POLICY = "POL-AUTO-990002";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockBean private PolicyCenterClient policyCenterClient;
    @MockBean private ClaimCenterClient claimCenterClient;

    private String report(String state, String channel) throws Exception {
        return objectMapper.writeValueAsString(new FNOLPayload(POLICY, LocalDate.now().minusDays(3), state,
                "Rear-ended at a light, bumper pushed in.", "Avery Johnson", null, null,
                List.of("mock://photo/normal.jpg"), channel, null, null, new BigDecimal("2500")));
    }

    @Test
    @DisplayName("Health says new losses go to the claims system")
    void healthNamesTheIntakeMode() throws Exception {
        mockMvc.perform(get("/api/v1/health")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.intake").value(ClaimsSystemIntake.CLAIMS_SYSTEM));
    }

    @Test
    @DisplayName("Portal: the loss is filed in the claims system and the claimant gets its claim number; no analysis runs in the request")
    void portalFilesInTheClaimsSystem() throws Exception {
        when(claimCenterClient.submitFnol(any())).thenReturn(new ClaimCreateResponse("CLM-2026-0F0001", "OPEN"));

        mockMvc.perform(post("/api/v1/portal/claims").contentType(MediaType.APPLICATION_JSON).content(report("CA", "API")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.claimId").value("CLM-2026-0F0001"))
                .andExpect(jsonPath("$.stage").value("RECEIVED"))
                .andExpect(jsonPath("$.lineOfBusiness").value("AUTO"));

        ArgumentCaptor<FNOLPayload> filed = ArgumentCaptor.forClass(FNOLPayload.class);
        verify(claimCenterClient).submitFnol(filed.capture());
        assertThat(filed.getValue().channel()).isEqualTo("WEB");   // the portal records its own channel
        verify(claimCenterClient, never()).createClaim(any());
        verifyNoInteractions(policyCenterClient);
    }

    @Test
    @DisplayName("Input the claimant must correct is refused (400) before anything is filed")
    void invalidInputIsNotFiled() throws Exception {
        mockMvc.perform(post("/api/v1/portal/claims").contentType(MediaType.APPLICATION_JSON).content(report("WA", "WEB")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
        verify(claimCenterClient, never()).submitFnol(any());
    }

    @Test
    @DisplayName("A loss the claims system already holds is refused as a duplicate (409)")
    void duplicateIsRefused() throws Exception {
        when(claimCenterClient.submitFnol(any())).thenThrow(new DuplicateLossException("CLM-2026-0F0009"));
        mockMvc.perform(post("/api/v1/portal/claims").contentType(MediaType.APPLICATION_JSON).content(report("CA", "WEB")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DUPLICATE_FNOL"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("CLM-2026-0F0009")));
    }

    @Test
    @DisplayName("If the claims system cannot be reached nothing is filed and the caller is told to retry (503)")
    void claimsSystemDown() throws Exception {
        when(claimCenterClient.submitFnol(any())).thenThrow(new ResourceAccessException("connection refused"));
        mockMvc.perform(post("/api/v1/portal/claims").contentType(MediaType.APPLICATION_JSON).content(report("CA", "WEB")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("CLAIMS_SYSTEM_UNAVAILABLE"))
                .andExpect(jsonPath("$.claimId").doesNotExist());
    }

    @Test
    @DisplayName("Tracking a claim still being analysed answers from the claims system; the policy number must match")
    void trackingBeforeTheAnalysisFinishes() throws Exception {
        when(claimCenterClient.getClaim("CLM-2026-0F0002")).thenReturn(new Claim("CLM-2026-0F0002", "OPEN",
                POLICY, LocalDate.now().minusDays(3), "CA", null, null));
        when(claimCenterClient.getClaim("CLM-2026-0F0003")).thenThrow(
                HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", null, null, null));

        mockMvc.perform(get("/api/v1/portal/claims/clm-2026-0f0002").param("policyNumber", POLICY.toLowerCase()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("RECEIVED"))
                .andExpect(jsonPath("$.claimId").value("CLM-2026-0F0002"));
        mockMvc.perform(get("/api/v1/portal/claims/CLM-2026-0F0002").param("policyNumber", "POL-AUTO-112233"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/portal/claims/CLM-2026-0F0003").param("policyNumber", POLICY))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Agent phone intake: files in the claims system with the adjuster token and keeps the PHONE channel")
    void phoneIntakeFilesInTheClaimsSystem() throws Exception {
        when(claimCenterClient.submitFnol(any())).thenReturn(new ClaimCreateResponse("CLM-2026-0F0004", "OPEN"));

        mockMvc.perform(post("/api/v1/fnol/report").contentType(MediaType.APPLICATION_JSON).content(report("CA", "PHONE")))
                .andExpect(status().isUnauthorized());
        verify(claimCenterClient, never()).submitFnol(any());

        mockMvc.perform(post("/api/v1/fnol/report").header("X-Adjuster-Token", "test-adjuster-token")
                        .contentType(MediaType.APPLICATION_JSON).content(report("CA", "PHONE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.claimId").value("CLM-2026-0F0004"))
                .andExpect(jsonPath("$.status").value("OPEN"));
        ArgumentCaptor<FNOLPayload> filed = ArgumentCaptor.forClass(FNOLPayload.class);
        verify(claimCenterClient).submitFnol(filed.capture());
        assertThat(filed.getValue().channel()).isEqualTo("PHONE");
    }

    @Test
    @DisplayName("A filed claim whose analysis failed shows the claimant that a specialist will contact them")
    void failedAnalysisShowsSpecialist() {
        ClaimEntity c = new ClaimEntity();
        c.claimId = "CLM-2026-0F0005";
        c.status = "HUMAN_REVIEW";
        c.failureReason = "Possible duplicate";
        assertThat(PortalController.stage(c, false)[0]).isEqualTo("SPECIALIST");
        c.failureReason = null;
        assertThat(PortalController.stage(c, false)[0]).isEqualTo("IN_REVIEW");
    }
}
