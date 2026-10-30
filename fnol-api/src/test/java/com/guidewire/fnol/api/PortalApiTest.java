package com.guidewire.fnol.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guidewire.fnol.api.persistence.ClaimEntity;
import com.guidewire.fnol.api.persistence.ClaimRepository;
import com.guidewire.fnol.api.persistence.UploadRequestEntity;
import com.guidewire.fnol.api.persistence.UploadRequestRepository;
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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The claimant's FNOL portal: reports a loss and tracks it without the adjuster token and without AI internals. */
@SpringBootTest
@AutoConfigureMockMvc
class PortalApiTest {

    private static final String LETTER = "Dear Avery Johnson,\n\nWe have received your claim ...";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ClaimRepository claims;
    @Autowired private UploadRequestRepository uploads;
    @MockBean private PolicyCenterClient policyCenterClient;
    @MockBean private ClaimCenterClient claimCenterClient;

    @Test
    @DisplayName("A policyholder reports a loss without the adjuster token and gets a receipt with no AI internals")
    void reportReturnsClaimantReceipt() throws Exception {
        LocalDate start = LocalDate.now().minusMonths(6);
        when(policyCenterClient.getPolicy("POL-AUTO-112233")).thenReturn(new Policy("POL-AUTO-112233", "AUTO", "ACTIVE",
                start, start.plusYears(1), "Avery Johnson", "CA", "2025 Camry", List.of()));
        when(policyCenterClient.getCoverages("POL-AUTO-112233")).thenReturn(List.of(
                new Coverage("COLLISION", new BigDecimal("50000"), new BigDecimal("1000"))));
        when(claimCenterClient.createClaim(any())).thenReturn(new ClaimCreateResponse("CLM-2026-000861", "DRAFT_PENDING_REVIEW"));
        when(claimCenterClient.getClaim(anyString())).thenReturn(new Claim("CLM-2026-000861", "DRAFT_PENDING_REVIEW",
                "POL-AUTO-112233", LocalDate.now().minusDays(77), "CA", null, null, null,
                List.of(new ClaimAttachment("acknowledgement-letter.txt", "text/plain", LETTER)), List.of()));

        FNOLPayload payload = new FNOLPayload("POL-AUTO-112233", LocalDate.now().minusDays(77), "CA",
                "Reversed into a bollard in a car park.", "Avery Johnson", null, null, List.of("mock://photo/normal.jpg"),
                "PHONE", null, null, new BigDecimal("2500"), List.of("CLOSE_UP"));
        JsonNode receipt = objectMapper.readTree(mockMvc.perform(post("/api/v1/portal/claims")
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertThat(receipt.path("claimId").asText()).isEqualTo("CLM-2026-000861");
        assertThat(receipt.path("stage").asText()).isEqualTo("IN_REVIEW");
        assertThat(receipt.path("acknowledgeBy").isNull()).isFalse();
        assertThat(receipt.path("letter").asText()).isEqualTo(LETTER);
        // Nothing about how the claim was assessed reaches the claimant.
        for (String internal : List.of("branchA", "branchB", "explanation", "reserve", "gateFlags", "inputFlags",
                "status", "eligibleForAdvisoryPrefill", "notes", "policyContext")) {
            assertThat(receipt.has(internal)).as(internal).isFalse();
        }
        // Recorded as a web report whatever the portal sent.
        assertThat(claims.findById("CLM-2026-000861").orElseThrow().channel).isEqualTo("WEB");
    }

    @Test
    @DisplayName("Tracking needs the matching policy number; a wrong one looks the same as an unknown claim")
    void trackingNeedsThePolicyNumber() throws Exception {
        String claimId = saveClaim("HUMAN_REVIEW");
        JsonNode view = objectMapper.readTree(mockMvc.perform(get("/api/v1/portal/claims/" + claimId)
                        .param("policyNumber", " pol-auto-112233 "))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(view.path("stage").asText()).isEqualTo("IN_REVIEW");
        assertThat(view.has("reserve")).isFalse();
        assertThat(view.has("adjusterNotes")).isFalse();

        String wrong = mockMvc.perform(get("/api/v1/portal/claims/" + claimId).param("policyNumber", "POL-AUTO-999999"))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        String unknown = mockMvc.perform(get("/api/v1/portal/claims/CLM-NOPE-1").param("policyNumber", "POL-AUTO-112233"))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        assertThat(wrong).isEqualTo(unknown);
    }

    @Test
    @DisplayName("A missing body or missing fields is a 400 that names fields, never Java classes")
    void badReportsAreClientErrors() throws Exception {
        String empty = mockMvc.perform(post("/api/v1/portal/claims").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(empty).path("error").asText()).isEqualTo("VALIDATION_ERROR");

        String missing = objectMapper.readTree(mockMvc.perform(post("/api/v1/portal/claims")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString())
                .path("message").asText();
        assertThat(missing).contains("policyNumber", "incidentDate").doesNotContain("com.guidewire", "FNOLPayload");
    }

    @Test
    @DisplayName("An open photo request asks the claimant to act; decisions are shown in plain language")
    void stagesInPlainLanguage() throws Exception {
        String claimId = saveClaim("HUMAN_REVIEW");
        UploadRequestEntity u = new UploadRequestEntity();
        u.requestId = UUID.randomUUID().toString();
        u.claimId = claimId;
        u.tokenSha256 = UUID.randomUUID().toString().replace("-", "");
        u.requestedAngles = "FRONT,REAR";
        u.requestedBy = "ADJ-001";
        u.status = "OPEN";
        u.createdAt = Instant.now();
        u.expiresAt = Instant.now().plus(Duration.ofDays(7));
        uploads.save(u);
        JsonNode view = objectMapper.readTree(mockMvc.perform(get("/api/v1/portal/claims/" + claimId)
                        .param("policyNumber", "POL-AUTO-112233"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(view.path("stage").asText()).isEqualTo("ACTION_NEEDED");
        assertThat(view.path("photosRequested").toString()).isEqualTo("[\"FRONT\",\"REAR\"]");

        ClaimEntity approved = new ClaimEntity();
        approved.status = "APPROVED_BY_ADJUSTER";
        approved.claimId = "CLM-X";
        assertThat(PortalController.stage(approved, false)[0]).isEqualTo("ACCEPTED");
        approved.status = "INVESTIGATION_REQUIRED";
        assertThat(PortalController.stage(approved, false)[2]).doesNotContainIgnoringCase("investigat")
                .doesNotContainIgnoringCase("fraud");
        approved.claimId = "REJ-1234";
        assertThat(PortalController.stage(approved, false)[0]).isEqualTo("SPECIALIST");
    }

    private String saveClaim(String status) {
        ClaimEntity e = new ClaimEntity();
        e.claimId = "CLM-P-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        e.policyNumber = "POL-AUTO-112233";
        e.status = status;
        e.incidentDate = LocalDate.now().minusDays(9);
        e.state = "CA";
        e.reserve = new BigDecimal("4830");
        e.adjusterNotes = "internal note";
        e.createdAt = Instant.now();
        e.updatedAt = e.createdAt;
        claims.save(e);
        return e.claimId;
    }
}
