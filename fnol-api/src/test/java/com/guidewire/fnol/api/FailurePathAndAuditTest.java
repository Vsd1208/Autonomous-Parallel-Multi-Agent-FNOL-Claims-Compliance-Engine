package com.guidewire.fnol.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guidewire.fnol.api.persistence.AuditEntity;
import com.guidewire.fnol.api.persistence.AuditRepository;
import com.guidewire.fnol.api.persistence.ClaimEntity;
import com.guidewire.fnol.api.persistence.ClaimRepository;
import com.guidewire.fnol.common.Models.*;
import com.guidewire.fnol.common.ProcessingTimeoutException;
import com.guidewire.fnol.enrichment.ClaimCenterClient;
import com.guidewire.fnol.enrichment.PolicyCenterClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** P4: failed intakes are persisted and audited. P5: the audit chain detects tampering. */
@SpringBootTest
@AutoConfigureMockMvc
class FailurePathAndAuditTest {

    private static final String SSN = "987-65-4321";
    private static final AtomicInteger DAYS_AGO = new AtomicInteger(30);

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ClaimRepository claims;
    @Autowired private AuditRepository audits;
    @MockBean private PolicyCenterClient policyCenterClient;
    @MockBean private ClaimCenterClient claimCenterClient;
    @Value("${fnol.security.adjuster-token}") private String token;

    private static final AtomicInteger CLAIM_SEQ = new AtomicInteger(900);

    @BeforeEach
    void stubGuidewire() {
        LocalDate start = LocalDate.now().minusMonths(6);
        when(policyCenterClient.getPolicy("POL-AUTO-112233")).thenReturn(new Policy(
                "POL-AUTO-112233", "AUTO", "ACTIVE", start, start.plusYears(1),
                "Avery Johnson", "CA", "2025 Camry", List.of()));
        when(policyCenterClient.getCoverages("POL-AUTO-112233")).thenReturn(List.of(
                new Coverage("COLLISION", new BigDecimal("50000"), new BigDecimal("1000"))));
        when(claimCenterClient.createClaim(any())).thenAnswer(inv ->
                new ClaimCreateResponse("CLM-2026-%06d".formatted(CLAIM_SEQ.getAndIncrement()), "OPEN"));
    }

    @Test
    @DisplayName("D6: a compliance block returns 422 and saves a BLOCKED_COMPLIANCE claim with an Intake audit event")
    void complianceBlockIsPersisted() throws Exception {
        FNOLPayload payload = payload("Collision. Medical record MRN-123456 attached. SSN " + SSN);

        JsonNode body = postIntake(payload, 422);
        assertThat(body.path("error").asText()).isEqualTo("COMPLIANCE_BLOCK");

        ClaimEntity saved = savedFailure(body, "BLOCKED_COMPLIANCE");
        assertThat(saved.failureReason).contains("MRN");
        assertIntakeAudit(saved.claimId, "COMPLIANCE_BLOCK");
    }

    @Test
    @DisplayName("D6: an uncovered policy returns 502 and saves a HUMAN_REVIEW claim with an Intake audit event")
    void uncoveredPolicyIsPersisted() throws Exception {
        when(policyCenterClient.getCoverages("POL-AUTO-112233")).thenReturn(List.of(
                new Coverage("LIABILITY", new BigDecimal("100000"), new BigDecimal("0"))));

        JsonNode body = postIntake(payload("Scraped a pole in a parking lot."), 502);
        assertThat(body.path("error").asText()).isEqualTo("EXTERNAL_OR_PROCESSING_ERROR");

        ClaimEntity saved = savedFailure(body, "HUMAN_REVIEW");
        assertThat(saved.failureReason).contains("not eligible");
        assertIntakeAudit(saved.claimId, "EXTERNAL_OR_PROCESSING_ERROR");
    }

    @Test
    @DisplayName("D6: a timeout returns 504 and saves a HUMAN_REVIEW claim; a corrected resubmission is accepted")
    void timeoutIsPersistedAndResubmissionAllowed() throws Exception {
        when(claimCenterClient.createClaim(any()))
                .thenThrow(new ProcessingTimeoutException("ClaimCenter did not answer in time"));
        FNOLPayload payload = payload("Scraped a pole in a parking lot.");

        JsonNode body = postIntake(payload, 504);
        assertThat(body.path("error").asText()).isEqualTo("PROCESSING_TIMEOUT");
        ClaimEntity saved = savedFailure(body, "HUMAN_REVIEW");
        assertIntakeAudit(saved.claimId, "PROCESSING_TIMEOUT");

        // The REJ- row has no ClaimCenter claim, so it must not count as a duplicate.
        doAnswer(inv -> new ClaimCreateResponse("CLM-2026-%06d".formatted(CLAIM_SEQ.getAndIncrement()), "OPEN"))
                .when(claimCenterClient).createClaim(any());
        postIntake(payload, 200);
    }

    @Test
    @DisplayName("D9: a PolicyCenter read timeout inside Branch A returns 504 and saves a HUMAN_REVIEW claim")
    void branchHttpTimeoutIsPersisted() throws Exception {
        when(policyCenterClient.getCoverages("POL-AUTO-112233"))
                .thenThrow(new org.springframework.web.client.ResourceAccessException("Read timed out"));

        JsonNode body = postIntake(payload("Scraped a pole in a parking lot."), 504);
        assertThat(body.path("error").asText()).isEqualTo("PROCESSING_TIMEOUT");
        assertIntakeAudit(savedFailure(body, "HUMAN_REVIEW").claimId, "PROCESSING_TIMEOUT");
    }

    @Test
    @DisplayName("D9: a ClaimCenter create timeout after the fan-out returns 504 and saves a HUMAN_REVIEW claim")
    void claimCenterCreateTimeoutIsPersisted() throws Exception {
        doThrow(new org.springframework.web.client.ResourceAccessException("Read timed out"))
                .when(claimCenterClient).createClaim(any());

        JsonNode body = postIntake(payload("Scraped a pole in a parking lot."), 504);
        assertThat(body.path("error").asText()).isEqualTo("PROCESSING_TIMEOUT");
        savedFailure(body, "HUMAN_REVIEW");
    }

    @Test
    @DisplayName("P5: GET /audit returns the chain in order with chainValid=true; a modified outputHash makes it false")
    void auditChainDetectsTampering() throws Exception {
        JsonNode created = postIntake(payload("Scraped a pole in a parking lot."), 200);
        String claimId = created.path("claimId").asText();

        JsonNode trail = getAudit(claimId);
        assertThat(trail.path("chainValid").asBoolean()).isTrue();
        assertThat(trail.path("records")).hasSize(8);   // 7 pipeline events + the stored evidence reference
        assertThat(trail.path("records").get(7).path("agentName").asText()).isEqualTo("EvidenceStore");
        assertThat(trail.path("records").get(0).path("seq").asInt()).isEqualTo(1);
        assertThat(trail.path("records").get(6).path("agentName").asText()).isEqualTo("ReconciliationGate");

        AuditEntity victim = audits.findByClaimIdOrderBySeqAscTimestampAsc(claimId).get(2);
        victim.outputHash = "0".repeat(64);
        audits.save(victim);

        assertThat(getAudit(claimId).path("chainValid").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("P5: no audit hash equals a hash of text containing the raw SSN")
    void auditNeverStoresRawPayloadHash() throws Exception {
        FNOLPayload payload = payload("Avery Johnson SSN " + SSN + " scraped a pole.");
        String claimId = postIntake(payload, 200).path("claimId").asText();

        String rawPayloadHash = sha256(String.valueOf(payload));
        assertThat(audits.findByClaimIdOrderBySeqAscTimestampAsc(claimId))
                .allSatisfy(a -> assertThat(a.inputHash).isNotEqualTo(rawPayloadHash));
    }

    private FNOLPayload payload(String description) {
        return new FNOLPayload("POL-AUTO-112233", LocalDate.now().minusDays(DAYS_AGO.getAndIncrement()), "CA",
                description, "Avery Johnson", "01/02/1980", SSN, List.of("mock://photo/normal.jpg"), "WEB");
    }

    private JsonNode postIntake(FNOLPayload payload, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/fnol/intake").header("X-Adjuster-Token", "test-adjuster-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().is(expectedStatus))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode getAudit(String claimId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/fnol/" + claimId + "/audit")
                        .header("X-Adjuster-Token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.claimId").value(claimId))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private ClaimEntity savedFailure(JsonNode errorBody, String expectedStatus) {
        String claimId = errorBody.path("claimId").asText();
        assertThat(claimId).matches("REJ-[0-9a-f]{8}");
        ClaimEntity saved = claims.findById(claimId).orElseThrow();
        assertThat(saved.status).isEqualTo(expectedStatus);
        assertThat(saved.estimatedDamage).isEqualByComparingTo("0");
        return saved;
    }

    private void assertIntakeAudit(String claimId, String errorCode) {
        List<AuditEntity> events = audits.findByClaimIdOrderBySeqAscTimestampAsc(claimId);
        // The failed intake, then the evidence kept for the adjuster (a mock:// reference here).
        assertThat(events).extracting(e -> e.agentName).containsExactly("Intake", "EvidenceStore");
        assertThat(events.get(0).status).isEqualTo(errorCode);
        assertThat(events.get(0).chainHash).hasSize(64);
    }

    private static String sha256(String s) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}
