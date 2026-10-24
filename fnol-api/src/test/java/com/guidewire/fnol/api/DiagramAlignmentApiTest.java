package com.guidewire.fnol.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guidewire.fnol.api.persistence.AuditEntity;
import com.guidewire.fnol.api.persistence.AuditRepository;
import com.guidewire.fnol.api.persistence.ClaimEntity;
import com.guidewire.fnol.api.persistence.ClaimRepository;
import com.guidewire.fnol.common.Models.*;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Architecture-diagram promises at the API: Human Review Point 3 can modify findings and require
 * investigation; any processing error escalates to a human; duplicates require human verification.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DiagramAlignmentApiTest {

    private static final String TOKEN = "test-adjuster-token";
    private static final AtomicInteger DAYS_AGO = new AtomicInteger(60);
    private static final AtomicInteger CLAIM_SEQ = new AtomicInteger(5000);

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ClaimRepository claims;
    @Autowired private AuditRepository audits;
    @MockBean private PolicyCenterClient policyCenterClient;
    @MockBean private ClaimCenterClient claimCenterClient;

    @BeforeEach
    void stubGuidewire() {
        LocalDate start = LocalDate.now().minusMonths(6);
        when(policyCenterClient.getPolicy("POL-AUTO-112233")).thenReturn(new Policy(
                "POL-AUTO-112233", "AUTO", "ACTIVE", start, start.plusYears(1),
                "Avery Johnson", "CA", "2025 Camry", List.of()));
        when(policyCenterClient.getCoverages("POL-AUTO-112233")).thenReturn(List.of(
                new Coverage("COLLISION", new BigDecimal("50000"), new BigDecimal("1000"))));
        doAnswer(inv -> new ClaimCreateResponse("CLM-2026-%06d".formatted(CLAIM_SEQ.getAndIncrement()), "DRAFT_PENDING_REVIEW"))
                .when(claimCenterClient).createClaim(any());
    }

    // ── Human Review Point 3: modify findings, require investigation ───────────────

    @Test
    @DisplayName("An adjuster can override the AI reserve with a reason; ClaimCenter receives it as a tracked action")
    void adjusterCanModifyReserve() throws Exception {
        String claimId = saveClaim("HUMAN_REVIEW");

        review(claimId, new AdjusterReviewRequest("APPROVED_BY_ADJUSTER", "ADJ-009", "Second estimate received.",
                new BigDecimal("6100"), "Body shop estimate is higher than the photo estimate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.adjustedReserve").value(6100))
                .andExpect(jsonPath("$.overrideReason").value("Body shop estimate is higher than the photo estimate"));

        ClaimEntity saved = claims.findById(claimId).orElseThrow();
        assertThat(saved.reserve).isEqualByComparingTo("4830");            // AI recommendation kept
        assertThat(saved.adjusterReserve).isEqualByComparingTo("6100");

        ArgumentCaptor<ClaimUpdateRequest> update = ArgumentCaptor.forClass(ClaimUpdateRequest.class);
        verify(claimCenterClient).updateClaim(eq(claimId), update.capture());
        assertThat(update.getValue().status()).isEqualTo("OPEN");
        assertThat(update.getValue().action()).isEqualTo("APPROVED_BY_ADJUSTER");
        assertThat(update.getValue().adjustedReserve()).isEqualByComparingTo("6100");

        AuditEntity event = audits.findByClaimIdOrderBySeqAscTimestampAsc(claimId).get(0);
        assertThat(event.agentName).isEqualTo("AdjusterReview");
        assertThat(event.actor).isEqualTo("ADJ-009");
    }

    @Test
    @DisplayName("An adjusted reserve without a reason is refused with 400")
    void overrideNeedsReason() throws Exception {
        String claimId = saveClaim("HUMAN_REVIEW");

        review(claimId, new AdjusterReviewRequest("APPROVED_BY_ADJUSTER", "ADJ-009", "x", new BigDecimal("6100"), " "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
        assertThat(claims.findById(claimId).orElseThrow().status).isEqualTo("HUMAN_REVIEW");
    }

    @Test
    @DisplayName("An adjuster can require additional investigation, then decide later")
    void adjusterCanRequireInvestigation() throws Exception {
        String claimId = saveClaim("HUMAN_REVIEW");

        review(claimId, new AdjusterReviewRequest("INVESTIGATION_REQUIRED", "ADJ-009", "Possible staged loss."))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INVESTIGATION_REQUIRED"));
        verify(claimCenterClient).updateClaim(eq(claimId),
                eq(new ClaimUpdateRequest("UNDER_INVESTIGATION", "Possible staged loss.", "INVESTIGATION_REQUIRED", "ADJ-009", null)));

        review(claimId, new AdjusterReviewRequest("REJECTED_BY_ADJUSTER", "ADJ-009", "Investigation found fraud."))
                .andExpect(status().isOk());
        verify(claimCenterClient).updateClaim(eq(claimId),
                eq(new ClaimUpdateRequest("DENIED", "Investigation found fraud.", "REJECTED_BY_ADJUSTER", "ADJ-009", null)));
    }

    // ── Exception handling: any error escalates to a human ─────────────────────────

    @Test
    @DisplayName("An unexpected processing error returns 502 and is saved as a HUMAN_REVIEW REJ- claim")
    void unexpectedErrorIsEscalated() throws Exception {
        doThrow(new IllegalStateException("ClaimCenter rejected the claim payload"))
                .when(claimCenterClient).createClaim(any());

        JsonNode body = postIntake(payload(), 502);
        assertThat(body.path("error").asText()).isEqualTo("EXTERNAL_OR_PROCESSING_ERROR");
        assertThat(body.path("message").asText()).contains("escalated");

        ClaimEntity saved = claims.findById(body.path("claimId").asText()).orElseThrow();
        assertThat(saved.status).isEqualTo("HUMAN_REVIEW");
        assertThat(saved.failureReason).isEqualTo("ClaimCenter rejected the claim payload");
        assertThat(audits.findByClaimIdOrderBySeqAscTimestampAsc(saved.claimId))
                .extracting(a -> a.agentName).containsExactly("Intake");
    }

    // ── Duplicate check: require human verification ────────────────────────────────

    @Test
    @DisplayName("A duplicate returns 409 with the original claim, which is flagged and audited for verification")
    void duplicateIsFlaggedForVerification() throws Exception {
        FNOLPayload payload = payload();
        String originalId = postIntake(payload, 200).path("claimId").asText();

        JsonNode dup = postIntake(payload, 409);
        assertThat(dup.path("error").asText()).isEqualTo("DUPLICATE_FNOL");
        assertThat(dup.path("claimId").asText()).isEqualTo(originalId);

        ClaimEntity original = claims.findById(originalId).orElseThrow();
        assertThat(original.gateFlags).contains("DUPLICATE_SUBMISSION");
        List<AuditEntity> events = audits.findByClaimIdOrderBySeqAscTimestampAsc(originalId);
        assertThat(events.get(events.size() - 1).agentName).isEqualTo("DuplicateCheck");
        assertThat(events.get(events.size() - 1).status).isEqualTo("DUPLICATE_FNOL");
    }

    @Test
    @DisplayName("A duplicate of a claim filed with no human step puts that claim back in HUMAN_REVIEW")
    void duplicateOfUnreviewedStpClaimNeedsHuman() throws Exception {
        FNOLPayload payload = payload();
        ClaimEntity stp = entity("STRAIGHT_THROUGH_PROCESSING");
        stp.incidentDate = payload.incidentDate();
        claims.save(stp);

        postIntake(payload, 409);

        ClaimEntity after = claims.findById(stp.claimId).orElseThrow();
        assertThat(after.status).isEqualTo("HUMAN_REVIEW");
        assertThat(after.gateFlags).isEqualTo("DUPLICATE_SUBMISSION");
    }

    private ResultActions review(String claimId, AdjusterReviewRequest request) throws Exception {
        return mockMvc.perform(post("/api/v1/fnol/" + claimId + "/review")
                .header("X-Adjuster-Token", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));
    }

    private JsonNode postIntake(FNOLPayload payload, int expectedStatus) throws Exception {
        String body = mockMvc.perform(post("/api/v1/fnol/intake")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private static FNOLPayload payload() {
        return new FNOLPayload("POL-AUTO-112233", LocalDate.now().minusDays(DAYS_AGO.getAndIncrement()), "CA",
                "Scraped a pole in a parking lot.", "Avery Johnson", "01/02/1980", "123-45-6789",
                List.of("mock://photo/normal.jpg"), "PHONE");
    }

    private String saveClaim(String status) {
        ClaimEntity e = entity(status);
        claims.save(e);
        return e.claimId;
    }

    private static ClaimEntity entity(String status) {
        ClaimEntity e = new ClaimEntity();
        e.claimId = "CLM-D-" + UUID.randomUUID().toString().substring(0, 8);
        e.policyNumber = "POL-AUTO-112233";
        e.status = status;
        e.incidentDate = LocalDate.now().minusDays(3);
        e.state = "CA";
        e.estimatedDamage = new BigDecimal("4200");
        e.reserve = new BigDecimal("4830");
        e.createdAt = Instant.now();
        e.updatedAt = e.createdAt;
        return e;
    }
}
