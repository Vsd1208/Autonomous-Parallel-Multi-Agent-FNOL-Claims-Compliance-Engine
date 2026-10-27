package com.guidewire.fnol.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guidewire.fnol.api.persistence.*;
import com.guidewire.fnol.common.Models.ClaimUpdateRequest;
import com.guidewire.fnol.enrichment.ClaimCenterClient;
import com.guidewire.fnol.enrichment.PolicyCenterClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The adjuster requests more photos; the claimant adds them through a one-time link. */
@SpringBootTest
@AutoConfigureMockMvc
class UploadRequestWorkflowTest {

    private static final String TOKEN = "test-adjuster-token";
    /** A tiny valid PNG. */
    private static final String PNG = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ClaimRepository claims;
    @Autowired private UploadRequestRepository requests;
    @Autowired private ClaimDocumentRepository documents;
    @MockBean private PolicyCenterClient policyCenterClient;
    @MockBean private ClaimCenterClient claimCenterClient;

    @BeforeEach
    void resetClaimCenter() {
        reset(claimCenterClient);
    }

    @Test
    @DisplayName("Request → claimant sees views → uploads → photos stored with angles, claim back to HUMAN_REVIEW, link used up")
    void fullFlow() throws Exception {
        String claimId = saveClaim("HUMAN_REVIEW");

        JsonNode created = json(post("/api/v1/fnol/" + claimId + "/upload-requests", Map.of(
                "requestedBy", "ADJ-001", "angles", List.of("rear", "CLOSE_UP"),
                "message", "Please send a photo of the rear and a close-up of the dent.")), 200);
        String url = created.path("uploadUrl").asText();
        assertThat(url).startsWith("http://localhost:5173/upload/");
        String token = url.substring(url.lastIndexOf('/') + 1);
        assertThat(token).hasSizeGreaterThanOrEqualTo(43);   // 256 random bits, base64url
        ClaimEntity waiting = claims.findById(claimId).orElseThrow();
        assertThat(waiting.status).isEqualTo("NEEDS_MORE_INFO");
        assertThat(waiting.adjusterDecision).isEqualTo("NEEDS_MORE_INFO");
        // Only the token's hash is stored.
        assertThat(requests.findAll()).noneMatch(r -> token.equals(r.tokenSha256));

        // The claimant's page: no adjuster token, no personal data.
        String page = mockMvc.perform(get("/api/v1/public/upload/" + token)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode view = objectMapper.readTree(page);
        assertThat(view.path("angles")).extracting(JsonNode::asText).containsExactly("REAR", "CLOSE_UP");
        assertThat(view.path("status").asText()).isEqualTo("OPEN");
        assertThat(page).doesNotContain("Avery").doesNotContain("POL-AUTO");

        mockMvc.perform(publicPost(token, Map.of("evidenceUris", List.of(PNG, PNG), "evidenceAngles", List.of("REAR", "CLOSE_UP"))))
                .andExpect(status().isOk());

        assertThat(documents.findByClaimIdOrderByUploadedAtAscNameAsc(claimId))
                .extracting(d -> d.angle).containsExactlyInAnyOrder("REAR", "CLOSE_UP");
        assertThat(claims.findById(claimId).orElseThrow().status).isEqualTo("HUMAN_REVIEW");

        // Single use.
        mockMvc.perform(publicPost(token, Map.of("evidenceUris", List.of(PNG), "evidenceAngles", List.of("REAR"))))
                .andExpect(status().isGone());
        mockMvc.perform(get("/api/v1/public/upload/" + token)).andExpect(status().isGone());

        // ClaimCenter heard both steps; the audit chain has both events and is intact.
        ArgumentCaptor<ClaimUpdateRequest> updates = ArgumentCaptor.forClass(ClaimUpdateRequest.class);
        verify(claimCenterClient, times(2)).updateClaim(eq(claimId), updates.capture());
        assertThat(updates.getAllValues()).extracting(ClaimUpdateRequest::action).containsExactly("PHOTOS_REQUESTED", "PHOTOS_RECEIVED");
        JsonNode trail = json(get("/api/v1/fnol/" + claimId + "/audit"), 200);
        assertThat(trail.path("chainValid").asBoolean()).isTrue();
        assertThat(trail.path("records")).extracting(r -> r.path("agentName").asText())
                .contains("PhotoRequest", "EvidenceStore", "SupplementaryEvidence");
    }

    @Test
    @DisplayName("Expired, unknown and cancelled links are refused; only uploaded image data is accepted")
    void refusals() throws Exception {
        String claimId = saveClaim("HUMAN_REVIEW");
        String token = tokenOf(json(post("/api/v1/fnol/" + claimId + "/upload-requests",
                Map.of("requestedBy", "ADJ-001", "angles", List.of("FRONT"))), 200));

        mockMvc.perform(get("/api/v1/public/upload/not-a-real-token")).andExpect(status().isNotFound());
        // A link to another site is not a photo upload.
        mockMvc.perform(publicPost(token, Map.of("evidenceUris", List.of("https://example.com/x.jpg"), "evidenceAngles", List.of("FRONT"))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(publicPost(token, Map.of("evidenceUris", List.of(PNG), "evidenceAngles", List.of("ROOFTOP"))))
                .andExpect(status().isBadRequest());

        UploadRequestEntity r = requests.findByClaimIdOrderByCreatedAtAsc(claimId).get(0);
        r.expiresAt = Instant.now().minusSeconds(60);
        requests.save(r);
        mockMvc.perform(publicPost(token, Map.of("evidenceUris", List.of(PNG), "evidenceAngles", List.of("FRONT"))))
                .andExpect(status().isGone());

        String claim2 = saveClaim("HUMAN_REVIEW");
        JsonNode second = json(post("/api/v1/fnol/" + claim2 + "/upload-requests",
                Map.of("requestedBy", "ADJ-001", "angles", List.of("LEFT"))), 200);
        perform(post("/api/v1/fnol/" + claim2 + "/upload-requests/" + second.path("requestId").asText() + "/cancel",
                Map.of("adjusterId", "ADJ-001"))).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/public/upload/" + tokenOf(second))).andExpect(status().isGone());
    }

    @Test
    @DisplayName("The adjuster side needs the token; a decided claim cannot get a photo request")
    void adjusterSideIsProtected() throws Exception {
        String claimId = saveClaim("HUMAN_REVIEW");
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/fnol/" + claimId + "/upload-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("requestedBy", "x", "angles", List.of("FRONT")))))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/fnol/" + claimId + "/upload-requests")).andExpect(status().isUnauthorized());

        String decided = saveClaim("APPROVED_BY_ADJUSTER");
        perform(post("/api/v1/fnol/" + decided + "/upload-requests", Map.of("requestedBy", "ADJ-001", "angles", List.of("FRONT"))))
                .andExpect(status().isConflict());
    }

    private static String tokenOf(JsonNode created) {
        String url = created.path("uploadUrl").asText();
        return url.substring(url.lastIndexOf('/') + 1);
    }

    private MockHttpServletRequestBuilder post(String url, Object body) throws Exception {
        return MockMvcRequestBuilders.post(url).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
    }

    private MockHttpServletRequestBuilder publicPost(String token, Object body) throws Exception {
        return post("/api/v1/public/upload/" + token, body);
    }

    private ResultActions perform(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("X-Adjuster-Token", TOKEN));
    }

    private JsonNode json(MockHttpServletRequestBuilder request, int expected) throws Exception {
        return objectMapper.readTree(perform(request).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString());
    }

    private String saveClaim(String status) {
        ClaimEntity e = new ClaimEntity();
        e.claimId = "CLM-U-" + UUID.randomUUID().toString().substring(0, 8);
        e.policyNumber = "POL-AUTO-112233";
        e.status = status;
        e.incidentDate = LocalDate.now().minusDays(3);
        e.state = "CA";
        e.estimatedDamage = new BigDecimal("4200");
        e.reserve = new BigDecimal("3200");
        e.severityGate = "LOW";
        e.createdAt = Instant.now();
        e.updatedAt = e.createdAt;
        claims.save(e);
        return e.claimId;
    }
}
