package com.guidewire.fnol.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guidewire.fnol.api.persistence.AuditEntity;
import com.guidewire.fnol.api.persistence.AuditRepository;
import com.guidewire.fnol.api.persistence.ClaimEntity;
import com.guidewire.fnol.api.persistence.ClaimRepository;
import com.guidewire.fnol.common.Models.AdjusterReviewRequest;
import com.guidewire.fnol.enrichment.ClaimCenterClient;
import com.guidewire.fnol.enrichment.PolicyCenterClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** P6 / D3: adjuster endpoints need the token, review is state-checked and audited, CORS is restricted. */
@SpringBootTest
@AutoConfigureMockMvc
class ReviewSecurityTest {

    private static final String TOKEN = "test-adjuster-token";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ClaimRepository claims;
    @Autowired private AuditRepository audits;
    @MockBean private PolicyCenterClient policyCenterClient;
    @MockBean private ClaimCenterClient claimCenterClient;

    @ParameterizedTest
    @ValueSource(strings = {"/review", "/audit", "/audit/pdf", "/explanation"})
    @DisplayName("Adjuster endpoints return 401 without a token or with a wrong token")
    void adjusterEndpointsRequireToken(String suffix) throws Exception {
        String claimId = saveClaim("HUMAN_REVIEW");
        String path = "/api/v1/fnol/" + claimId + suffix;
        var noToken = suffix.equals("/review")
                ? post(path).contentType(MediaType.APPLICATION_JSON).content(reviewBody("APPROVED_BY_ADJUSTER"))
                : get(path);
        mockMvc.perform(noToken)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));

        var wrongToken = suffix.equals("/review")
                ? post(path).header("X-Adjuster-Token", "nope").contentType(MediaType.APPLICATION_JSON)
                        .content(reviewBody("APPROVED_BY_ADJUSTER"))
                : get(path).header("X-Adjuster-Token", "nope");
        mockMvc.perform(wrongToken).andExpect(status().isUnauthorized());

        assertThat(claims.findById(claimId).orElseThrow().status).isEqualTo("HUMAN_REVIEW");
    }

    @Test
    @DisplayName("Intake, status and health stay open")
    void openEndpointsDoNotNeedToken() throws Exception {
        mockMvc.perform(get("/api/v1/health")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/fnol/status/" + saveClaim("HUMAN_REVIEW"))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("Review of a HUMAN_REVIEW claim succeeds and writes an AdjusterReview audit event")
    void reviewWritesAuditEvent() throws Exception {
        String claimId = saveClaim("HUMAN_REVIEW");

        review(claimId, "APPROVED_BY_ADJUSTER")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED_BY_ADJUSTER"));

        List<AuditEntity> events = audits.findByClaimIdOrderBySeqAscTimestampAsc(claimId);
        assertThat(events).hasSize(1);
        AuditEntity event = events.get(0);
        assertThat(event.agentName).isEqualTo("AdjusterReview");
        assertThat(event.status).isEqualTo("APPROVED_BY_ADJUSTER");
        assertThat(event.actor).isEqualTo("ADJ-007");
        assertThat(event.chainHash).hasSize(64);
    }

    @Test
    @DisplayName("PENDING_ADVISORY_REVIEW and NEEDS_MORE_INFO claims can be reviewed")
    void advisoryAndMoreInfoClaimsAreReviewable() throws Exception {
        String claimId = saveClaim("PENDING_ADVISORY_REVIEW");
        review(claimId, "NEEDS_MORE_INFO").andExpect(status().isOk());
        review(claimId, "APPROVED_BY_ADJUSTER").andExpect(status().isOk());
        assertThat(audits.findByClaimIdOrderBySeqAscTimestampAsc(claimId)).hasSize(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"STRAIGHT_THROUGH_PROCESSING", "APPROVED_BY_ADJUSTER", "REJECTED_BY_ADJUSTER",
            "CLOSED", "BLOCKED_COMPLIANCE"})
    @DisplayName("Review returns 409 INVALID_STATE for STP, already-decided and blocked claims")
    void reviewRejectsNonReviewableStatus(String status) throws Exception {
        String claimId = saveClaim(status);

        review(claimId, "APPROVED_BY_ADJUSTER")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("INVALID_STATE"));

        assertThat(claims.findById(claimId).orElseThrow().status).isEqualTo(status);
        assertThat(audits.findByClaimIdOrderBySeqAscTimestampAsc(claimId)).isEmpty();
    }

    @Test
    @DisplayName("A second final decision on the same claim is refused")
    void secondFinalDecisionIsRefused() throws Exception {
        String claimId = saveClaim("HUMAN_REVIEW");
        review(claimId, "REJECTED_BY_ADJUSTER").andExpect(status().isOk());
        review(claimId, "APPROVED_BY_ADJUSTER").andExpect(status().isConflict());
        assertThat(claims.findById(claimId).orElseThrow().adjusterDecision).isEqualTo("REJECTED_BY_ADJUSTER");
    }

    @Test
    @DisplayName("CORS allows the configured console origin and refuses others")
    void corsIsRestricted() throws Exception {
        mockMvc.perform(options("/api/v1/fnol/intake")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"));

        mockMvc.perform(options("/api/v1/fnol/intake")
                        .header(HttpHeaders.ORIGIN, "https://evil.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isForbidden());
    }

    private org.springframework.test.web.servlet.ResultActions review(String claimId, String decision) throws Exception {
        return mockMvc.perform(post("/api/v1/fnol/" + claimId + "/review")
                .header("X-Adjuster-Token", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(reviewBody(decision)));
    }

    private String reviewBody(String decision) throws Exception {
        return objectMapper.writeValueAsString(new AdjusterReviewRequest(decision, "ADJ-007", "Checked photos."));
    }

    private String saveClaim(String status) {
        ClaimEntity e = new ClaimEntity();
        e.claimId = "CLM-T-" + UUID.randomUUID().toString().substring(0, 8);
        e.policyNumber = "POL-AUTO-112233";
        e.status = status;
        e.incidentDate = LocalDate.now().minusDays(3);
        e.state = "CA";
        e.estimatedDamage = new BigDecimal("4200");
        e.reserve = new BigDecimal("4830");
        e.explanationJson = "{\"decision\":\"STRAIGHT_THROUGH\"}";
        e.createdAt = Instant.now();
        e.updatedAt = e.createdAt;
        claims.save(e);
        return e.claimId;
    }
}
