package com.guidewire.fnol.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guidewire.fnol.api.persistence.*;
import com.guidewire.fnol.common.Models.*;
import com.guidewire.fnol.enrichment.ClaimCenterClient;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The claims system owns the queue and the decision: decisions are recorded there first, and the engine's analysis
 * record, audit trail and feedback loop follow, also for a decision made in the claims system itself.
 */
@SpringBootTest(properties = "fnol.claims-events.secret=" + ClaimsSystemDecisionTest.SECRET)
@AutoConfigureMockMvc
class ClaimsSystemDecisionTest {

    static final String SECRET = "test-event-secret";
    private static final String TOKEN = "test-adjuster-token";
    private static final String POLICY = "POL-AUTO-990003";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ClaimRepository claims;
    @Autowired private AiFeedbackRepository feedback;
    @Autowired private AuditRepository audit;
    @MockBean private ClaimCenterClient claimCenter;

    private ClaimEntity analysed(String id, String status) {
        ClaimEntity c = new ClaimEntity();
        c.claimId = id;
        c.policyNumber = POLICY;
        c.status = status;
        c.incidentDate = LocalDate.now().minusDays(5);
        c.state = "CA";
        c.estimatedDamage = new BigDecimal("4200");
        c.reserve = new BigDecimal("3200");
        c.severityGate = "MODERATE";
        c.inputFlags = "HIGH_CLAIM_FREQUENCY";
        c.ccSyncStatus = "SYNCED";
        c.createdAt = Instant.now();
        c.updatedAt = c.createdAt;
        return claims.save(c);
    }

    private Claim filedWithAction(String id, Instant at) {
        return new Claim(id, "OPEN", POLICY, LocalDate.now().minusDays(5), "CA", null, null, "looks right", List.of(),
                List.of(new ClaimReviewAction(at, "APPROVED_BY_ADJUSTER", "OPEN", "ADJ-001", "looks right", null)),
                List.of(), List.of(), List.of(), "APPROVED_BY_ADJUSTER");
    }

    private ResultActions review(String id, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/fnol/" + id + "/review").header("X-Adjuster-Token", TOKEN)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions decidedEvent(String id, String decision, Instant at, Map<String, Object> extra) throws Exception {
        Map<String, Object> data = new HashMap<>(extra);
        data.put("claimId", id);
        data.put("decision", decision);
        data.put("adjusterId", "ADJ-CC");
        data.put("decidedAt", at.toString());
        String body = objectMapper.writeValueAsString(new ClaimEvent(UUID.randomUUID().toString(), "claim.decided",
                "claims-system", at, id, data));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return mockMvc.perform(post("/api/v1/events/claimcenter").contentType(MediaType.APPLICATION_JSON).content(body)
                .header(ClaimEventController.SIGNATURE_HEADER,
                        "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)))));
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    @Test
    @DisplayName("A decision goes to the claims system first with what the feedback loop needs; its later event changes nothing")
    void decisionIsRecordedInTheClaimsSystemFirst() throws Exception {
        String id = "CLM-2026-0D0001";
        analysed(id, "HUMAN_REVIEW");
        Instant at = now();
        when(claimCenter.recordDecision(eq(id), any())).thenReturn(filedWithAction(id, at));

        review(id, """
                {"decision":"APPROVED_BY_ADJUSTER","adjusterId":"ADJ-001","notes":"looks right","adjustedReserve":3500,
                 "overrideReason":"invoice","finalRepairCost":4500,"repairCostSource":"invoice"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED_BY_ADJUSTER"));

        ArgumentCaptor<ClaimUpdateRequest> sent = ArgumentCaptor.forClass(ClaimUpdateRequest.class);
        verify(claimCenter).recordDecision(eq(id), sent.capture());
        assertThat(sent.getValue().status()).isEqualTo("OPEN");
        assertThat(sent.getValue().action()).isEqualTo("APPROVED_BY_ADJUSTER");
        assertThat(sent.getValue().finalRepairCost()).isEqualByComparingTo("4500");
        assertThat(sent.getValue().repairCostSource()).isEqualTo("INVOICE");
        verify(claimCenter, never()).updateClaim(any(), any());

        ClaimEntity stored = claims.findById(id).orElseThrow();
        assertThat(stored.reviewedAt).isEqualTo(at);
        assertThat(stored.ccSyncStatus).isEqualTo("SYNCED");
        assertThat(feedback.findByClaimIdOrderByDecidedAtAsc(id)).hasSize(1);
        assertThat(feedback.findByClaimIdOrderByDecidedAtAsc(id).get(0).costSource).isEqualTo("INVOICE");
        long auditEvents = audit.findAll().stream().filter(a -> id.equals(a.claimId)).count();

        decidedEvent(id, "APPROVED_BY_ADJUSTER", at, Map.of()).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("duplicate"));
        assertThat(feedback.findByClaimIdOrderByDecidedAtAsc(id)).hasSize(1);
        assertThat(audit.findAll().stream().filter(a -> id.equals(a.claimId)).count()).isEqualTo(auditEvents);
    }

    @Test
    @DisplayName("If the claims system cannot take a decision nothing is recorded anywhere and the adjuster is told (503)")
    void claimsSystemDownRecordsNothing() throws Exception {
        String id = "CLM-2026-0D0002";
        analysed(id, "HUMAN_REVIEW");
        when(claimCenter.recordDecision(eq(id), any())).thenThrow(new ResourceAccessException("connection refused"));

        review(id, "{\"decision\":\"APPROVED_BY_ADJUSTER\",\"adjusterId\":\"ADJ-001\"}")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("CLAIMS_SYSTEM_UNAVAILABLE"));

        ClaimEntity stored = claims.findById(id).orElseThrow();
        assertThat(stored.status).isEqualTo("HUMAN_REVIEW");
        assertThat(stored.adjusterDecision).isNull();
        assertThat(feedback.findByClaimIdOrderByDecidedAtAsc(id)).isEmpty();
    }

    @Test
    @DisplayName("A decision the claims system refuses (claim finished there) leaves the record alone (409); a finished record is not sent")
    void refusedDecision() throws Exception {
        String id = "CLM-2026-0D0003";
        analysed(id, "HUMAN_REVIEW");
        when(claimCenter.recordDecision(eq(id), any())).thenThrow(
                HttpClientErrorException.create(HttpStatus.CONFLICT, "Conflict", null, null, null));
        review(id, "{\"decision\":\"APPROVED_BY_ADJUSTER\",\"adjusterId\":\"ADJ-001\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("INVALID_STATE"));
        assertThat(claims.findById(id).orElseThrow().adjusterDecision).isNull();

        String done = "CLM-2026-0D0004";
        analysed(done, "REJECTED_BY_ADJUSTER");
        review(done, "{\"decision\":\"APPROVED_BY_ADJUSTER\",\"adjusterId\":\"ADJ-001\"}").andExpect(status().isConflict());
        verify(claimCenter, never()).recordDecision(eq(done), any());
    }

    @Test
    @DisplayName("A decision made in the claims system itself: the record, audit and feedback loop follow; replays and older events do not")
    void decisionMadeInTheClaimsSystem() throws Exception {
        String id = "CLM-2026-0D0005";
        analysed(id, "HUMAN_REVIEW");
        when(claimCenter.getClaim(id)).thenReturn(filedWithAction(id, now()));
        Instant at = now();

        decidedEvent(id, "REJECTED_BY_ADJUSTER", at, Map.of("finalRepairCost", 900, "repairCostSource", "INVOICE"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("applied"));

        ClaimEntity stored = claims.findById(id).orElseThrow();
        assertThat(stored.status).isEqualTo("REJECTED_BY_ADJUSTER");
        assertThat(stored.adjusterId).isEqualTo("ADJ-CC");
        assertThat(stored.adjusterNotes).isEqualTo("looks right");   // read from the claim: the event carries no free text
        List<AiFeedbackEntity> rows = feedback.findByClaimIdOrderByDecidedAtAsc(id);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).finalRepairCost).isEqualByComparingTo("900");
        assertThat(rows.get(0).costSource).isEqualTo("INVOICE");

        decidedEvent(id, "REJECTED_BY_ADJUSTER", at, Map.of()).andExpect(jsonPath("$.status").value("duplicate"));
        decidedEvent(id, "APPROVED_BY_ADJUSTER", at.minusSeconds(60), Map.of())
                .andExpect(jsonPath("$.status").value("duplicate"));
        assertThat(claims.findById(id).orElseThrow().status).isEqualTo("REJECTED_BY_ADJUSTER");
        assertThat(feedback.findByClaimIdOrderByDecidedAtAsc(id)).hasSize(1);

        // The claims system has the last word: closing it later is followed, even though it is no longer reviewable.
        decidedEvent(id, "CLOSED", at.plusSeconds(60), Map.of()).andExpect(jsonPath("$.status").value("applied"));
        assertThat(claims.findById(id).orElseThrow().status).isEqualTo("CLOSED");
    }

    @Test
    @DisplayName("An event's unknown cost source can never teach the cost model")
    void unknownCostSourceDoesNotTeach() throws Exception {
        String id = "CLM-2026-0D0006";
        analysed(id, "HUMAN_REVIEW");
        when(claimCenter.getClaim(id)).thenReturn(filedWithAction(id, now()));
        decidedEvent(id, "APPROVED_BY_ADJUSTER", now(), Map.of("finalRepairCost", 700, "repairCostSource", "GUESS"))
                .andExpect(jsonPath("$.status").value("applied"));
        assertThat(feedback.findByClaimIdOrderByDecidedAtAsc(id).get(0).costSource).isEqualTo("ADJUSTER_JUDGEMENT");
    }

    @Test
    @DisplayName("A decision on a claim whose analysis has not finished is delivered again later (503), not lost")
    void decisionBeforeAnalysisIsRetried() throws Exception {
        String id = "CLM-2026-0D0007";
        when(claimCenter.getClaim(id)).thenReturn(new Claim(id, "SUBMITTED", POLICY, LocalDate.now(), "CA", null, null,
                null, List.of(), List.of(), List.of(), List.of(), List.of(), "ANALYSIS_PENDING"));
        decidedEvent(id, "APPROVED_BY_ADJUSTER", now(), Map.of()).andExpect(status().isServiceUnavailable());
        assertThat(claims.existsById(id)).isFalse();
    }

    @Test
    @DisplayName("The queue reads from the claims system: its claims, unanalysed ones too, with the AI's flags; failed direct intakes follow")
    void queueFromTheClaimsSystem() throws Exception {
        analysed("CLM-2026-0D0008", "PENDING_ADVISORY_REVIEW");   // the claims system's queue wins over this stored status
        ClaimEntity failed = analysed("REJ-0d000009", "HUMAN_REVIEW");
        failed.failureReason = "Timed out";
        claims.save(failed);
        when(claimCenter.listWorkQueues(any(), eq(0), eq(2))).thenReturn(new ClaimQueuePage(List.of(
                new ClaimQueueEntry("CLM-2026-0D0008", "DRAFT_PENDING_REVIEW", "HUMAN_REVIEW", POLICY,
                        new BigDecimal("3200"), Instant.now()),
                new ClaimQueueEntry("CLM-2026-0D0010", "SUBMITTED", "ANALYSIS_PENDING", POLICY, null, Instant.now())), 2));

        mockMvc.perform(get("/api/v1/fnol/queue").header("X-Adjuster-Token", TOKEN)
                        .param("status", "HUMAN_REVIEW,ANALYSIS_PENDING").param("page", "0").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("CLAIMS_SYSTEM"))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].status").value("HUMAN_REVIEW"))
                .andExpect(jsonPath("$.items[0].inputFlags").value("HIGH_CLAIM_FREQUENCY"))
                .andExpect(jsonPath("$.items[1].status").value("ANALYSIS_PENDING"))
                .andExpect(jsonPath("$.items[1].severityGate").doesNotExist());

        when(claimCenter.listWorkQueues(any(), eq(1), eq(2))).thenReturn(new ClaimQueuePage(List.of(), 2));
        String page2 = mockMvc.perform(get("/api/v1/fnol/queue").header("X-Adjuster-Token", TOKEN)
                        .param("status", "HUMAN_REVIEW").param("page", "1").param("size", "2"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(page2).contains("REJ-0d000009");   // not held by the claims system, still in the queue
    }

    @Test
    @DisplayName("If the claims system cannot be reached the queue falls back to the analysis records and says so")
    void queueFallback() throws Exception {
        analysed("CLM-2026-0D0011", "PENDING_ADVISORY_REVIEW");
        when(claimCenter.listWorkQueues(any(), anyInt(), anyInt())).thenThrow(new ResourceAccessException("down"));
        String body = mockMvc.perform(get("/api/v1/fnol/queue").header("X-Adjuster-Token", TOKEN)
                        .param("status", "PENDING_ADVISORY_REVIEW").param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("CLAIMS_SYSTEM_UNAVAILABLE"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("CLM-2026-0D0011");
    }

    @Test
    @DisplayName("A claim's status shows the claims system's queue at once; with no analysis it still opens, and viewing never writes a record")
    void claimFileReadsTheClaimsSystem() throws Exception {
        String id = "CLM-2026-0D0012";
        analysed(id, "HUMAN_REVIEW");
        when(claimCenter.getClaim(id)).thenReturn(filedWithAction(id, now()));
        mockMvc.perform(get("/api/v1/fnol/status/" + id).header("X-Adjuster-Token", TOKEN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED_BY_ADJUSTER"));
        assertThat(claims.findById(id).orElseThrow().status).isEqualTo("HUMAN_REVIEW");   // followed by the event, not by a read

        String pending = "CLM-2026-0D0013";
        when(claimCenter.getClaim(pending)).thenReturn(new Claim(pending, "SUBMITTED", POLICY, LocalDate.now(), "CA", null,
                null, null, List.of(), List.of(), List.of(), List.of(), List.of(), "ANALYSIS_PENDING"));
        mockMvc.perform(get("/api/v1/fnol/status/" + pending).header("X-Adjuster-Token", TOKEN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ANALYSIS_PENDING"));
        assertThat(claims.existsById(pending)).isFalse();

        String missing = "CLM-2026-0D0099";
        when(claimCenter.getClaim(missing)).thenThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", null, null, null));
        mockMvc.perform(get("/api/v1/fnol/status/" + missing).header("X-Adjuster-Token", TOKEN)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("A claim whose analysis never started can still be decided by a person, without the AI")
    void decidingWithoutTheAi() throws Exception {
        String id = "CLM-2026-0D0014";
        Instant at = now();
        when(claimCenter.getClaim(id)).thenReturn(new Claim(id, "DRAFT_PENDING_REVIEW", POLICY, LocalDate.now(), "CA", null,
                null, null, List.of(), List.of(), List.of(), List.of(), List.of(), "HUMAN_REVIEW"));
        when(claimCenter.recordDecision(eq(id), any())).thenReturn(filedWithAction(id, at));

        review(id, "{\"decision\":\"APPROVED_BY_ADJUSTER\",\"adjusterId\":\"ADJ-001\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED_BY_ADJUSTER"));
        ClaimEntity stored = claims.findById(id).orElseThrow();
        assertThat(stored.failureReason).isNotBlank();
        assertThat(stored.reviewedAt).isEqualTo(at);
    }
}
