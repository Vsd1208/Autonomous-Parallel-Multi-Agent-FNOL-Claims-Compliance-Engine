package com.guidewire.fnol.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guidewire.fnol.api.persistence.AiFeedbackEntity;
import com.guidewire.fnol.api.persistence.AiFeedbackRepository;
import com.guidewire.fnol.api.persistence.ClaimEntity;
import com.guidewire.fnol.api.persistence.ClaimRepository;
import com.guidewire.fnol.common.Models.AdjusterReviewRequest;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Assign an inspector, the inspector reports the accident-related cost, the adjuster decides. */
@SpringBootTest
@AutoConfigureMockMvc
class InspectionWorkflowTest {

    private static final String TOKEN = "test-adjuster-token";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ClaimRepository claims;
    @Autowired private AiFeedbackRepository feedback;
    @MockBean private PolicyCenterClient policyCenterClient;
    @MockBean private ClaimCenterClient claimCenterClient;

    @BeforeEach
    void resetClaimCenter() {
        reset(claimCenterClient);
    }

    @Test
    @DisplayName("Assign → claim waits in INSPECTION_ASSIGNED (no decision possible) → report → HUMAN_REVIEW with the inspected payment")
    void fullInspectionWorkflow() throws Exception {
        String claimId = saveClaim("9000");

        JsonNode assigned = json(post("/api/v1/fnol/" + claimId + "/inspections", Map.of(
                "inspectorId", "INS-101", "inspectionType", "field",
                "reason", "Claimed $9,000 but the photos support $4,200", "assignedBy", "ADJ-001")), 200);
        String inspectionId = assigned.path("inspectionId").asText();
        assertThat(assigned.path("status").asText()).isEqualTo("ASSIGNED");
        assertThat(assigned.path("inspectionType").asText()).isEqualTo("FIELD");
        assertThat(assigned.path("dueBy").asText()).isEqualTo(LocalDate.now().plusDays(3).toString());
        assertThat(claims.findById(claimId).orElseThrow().status).isEqualTo("INSPECTION_ASSIGNED");

        // While the inspector works, the adjuster cannot decide.
        perform(post("/api/v1/fnol/" + claimId + "/review",
                new AdjusterReviewRequest("APPROVED_BY_ADJUSTER", "ADJ-001", "ok"))).andExpect(status().isConflict());

        // Only the assigned inspector can report.
        perform(post("/api/v1/fnol/" + claimId + "/inspections/" + inspectionId + "/report",
                report("INS-102", "5000"))).andExpect(status().isConflict());

        JsonNode reported = json(post("/api/v1/fnol/" + claimId + "/inspections/" + inspectionId + "/report",
                report("INS-101", "5000")), 200);
        assertThat(reported.path("status").asText()).isEqualTo("COMPLETED");
        // min(claimed 9000, inspected 5000) − deductible 1000
        assertThat(reported.path("recommendedPayment").decimalValue()).isEqualByComparingTo("4000");
        assertThat(claims.findById(claimId).orElseThrow().status).isEqualTo("HUMAN_REVIEW");

        perform(post("/api/v1/fnol/" + claimId + "/inspections/" + inspectionId + "/report",
                report("INS-101", "5000"))).andExpect(status().isConflict());

        // ClaimCenter heard about both steps.
        ArgumentCaptor<ClaimUpdateRequest> updates = ArgumentCaptor.forClass(ClaimUpdateRequest.class);
        verify(claimCenterClient, times(2)).updateClaim(eq(claimId), updates.capture());
        assertThat(updates.getAllValues()).extracting(ClaimUpdateRequest::action)
                .containsExactly("INSPECTION_ASSIGNED", "INSPECTION_COMPLETED");
        assertThat(updates.getAllValues().get(0).status()).isEqualTo("PENDING_INSPECTION");

        JsonNode trail = json(get("/api/v1/fnol/" + claimId + "/audit"), 200);
        assertThat(trail.path("chainValid").asBoolean()).isTrue();
        assertThat(trail.path("records")).extracting(r -> r.path("agentName").asText())
                .containsExactly("InspectionAssignment", "InspectionReport");

        // The adjuster can now decide again.
        perform(post("/api/v1/fnol/" + claimId + "/review", new AdjusterReviewRequest("APPROVED_BY_ADJUSTER",
                "ADJ-001", "Approved at the inspected amount", new BigDecimal("4000"), "Inspection report")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Cancelling an open inspection returns the claim to HUMAN_REVIEW; a decided claim cannot be inspected")
    void cancelAndInvalidStates() throws Exception {
        String claimId = saveClaim(null);
        String inspectionId = json(post("/api/v1/fnol/" + claimId + "/inspections", Map.of(
                "inspectorId", "INS-102", "inspectionType", "DESK", "reason", "Possible earlier damage",
                "assignedBy", "ADJ-001")), 200).path("inspectionId").asText();

        // One open inspection at a time.
        perform(post("/api/v1/fnol/" + claimId + "/inspections", Map.of("inspectorId", "INS-101",
                "inspectionType", "FIELD", "reason", "again", "assignedBy", "ADJ-001"))).andExpect(status().isConflict());

        json(post("/api/v1/fnol/" + claimId + "/inspections/" + inspectionId + "/cancel", Map.of("adjusterId", "ADJ-001")), 200);
        assertThat(claims.findById(claimId).orElseThrow().status).isEqualTo("HUMAN_REVIEW");

        ClaimEntity decided = claims.findById(saveClaim(null)).orElseThrow();
        decided.status = "APPROVED_BY_ADJUSTER";
        claims.save(decided);
        perform(post("/api/v1/fnol/" + decided.claimId + "/inspections", Map.of("inspectorId", "INS-101",
                "inspectionType", "FIELD", "reason", "late", "assignedBy", "ADJ-001"))).andExpect(status().isConflict());
    }

    @Test
    @DisplayName("Inspection endpoints need the adjuster token; bad input is rejected")
    void securityAndValidation() throws Exception {
        String claimId = saveClaim(null);
        mockMvc.perform(get("/api/v1/inspectors")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/fnol/" + claimId + "/inspections")).andExpect(status().isUnauthorized());
        assertThat(json(get("/api/v1/inspectors"), 200)).hasSize(InspectionController.INSPECTORS.size());

        perform(post("/api/v1/fnol/" + claimId + "/inspections", Map.of("inspectorId", "INS-999",
                "inspectionType", "FIELD", "reason", "x", "assignedBy", "ADJ-001"))).andExpect(status().isBadRequest());
        perform(post("/api/v1/fnol/" + claimId + "/inspections", Map.of("inspectorId", "INS-101",
                "inspectionType", "DRONE", "reason", "x", "assignedBy", "ADJ-001"))).andExpect(status().isBadRequest());
        assertThat(claims.findById(claimId).orElseThrow().status).isEqualTo("HUMAN_REVIEW");
    }

    private static Map<String, Object> report(String inspectorId, String amount) {
        return Map.of("inspectorId", inspectorId, "accidentRelatedAmount", new BigDecimal(amount),
                "priorDamageFound", true, "findings", "Front bumper and headlight are new damage; the door dent is old and rusted.",
                "recommendation", "REDUCE");
    }

    /** A JSON POST (shadows MockMvcRequestBuilders.post). */
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder post(String url, Object body)
            throws Exception {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url)
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
    }

    private ResultActions perform(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        return mockMvc.perform(request.header("X-Adjuster-Token", TOKEN));
    }

    private JsonNode json(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                          int expected) throws Exception {
        String content = perform(request).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(content);
    }

    @Test
    @DisplayName("Sent to investigation: figures entered meanwhile are held; the investigator's offline cost is the claim's label")
    void investigationFindingsAreTheRepairCostLabel() throws Exception {
        String claimId = saveClaim("9000");

        // The adjuster sends the claim to investigation and enters an invoice figure: held, never learned from.
        perform(post("/api/v1/fnol/" + claimId + "/review", Map.of("decision", "INVESTIGATION_REQUIRED",
                "adjusterId", "ADJ-001", "finalRepairCost", 5000, "repairCostSource", "INVOICE")))
                .andExpect(status().isOk());
        assertThat(latestRow(claimId).costSource).isEqualTo("INVESTIGATION_HOLD");
        assertThat(latestRow(claimId).finalRepairCost).isEqualByComparingTo("5000");

        // The investigator goes out and reports the accident-related cost (earlier damage excluded).
        String inspectionId = json(post("/api/v1/fnol/" + claimId + "/inspections", Map.of(
                "inspectorId", "INS-101", "inspectionType", "FIELD",
                "reason", "Investigation: damage may predate the accident", "assignedBy", "ADJ-001")), 200)
                .path("inspectionId").asText();
        json(post("/api/v1/fnol/" + claimId + "/inspections/" + inspectionId + "/report", report("INS-101", "3200")), 200);
        assertThat(latestRow(claimId).costSource).isEqualTo("INVESTIGATION");
        assertThat(latestRow(claimId).finalRepairCost).isEqualByComparingTo("3200");

        // A later decision with a different invoice figure does not replace the investigator's cost.
        perform(post("/api/v1/fnol/" + claimId + "/review", Map.of("decision", "APPROVED_BY_ADJUSTER",
                "adjusterId", "ADJ-001", "finalRepairCost", 4000, "repairCostSource", "INVOICE")))
                .andExpect(status().isOk());
        AiFeedbackEntity last = latestRow(claimId);
        assertThat(last.adjusterDecision).isEqualTo("APPROVED_BY_ADJUSTER");
        assertThat(last.costSource).isEqualTo("INVESTIGATION");
        assertThat(last.finalRepairCost).isEqualByComparingTo("3200");
    }

    @Test
    @DisplayName("Without an investigation, an inspection's cost and an adjuster's evidence are used as before")
    void noInvestigationKeepsTheAdjustersEvidence() throws Exception {
        String claimId = saveClaim(null);
        perform(post("/api/v1/fnol/" + claimId + "/review", Map.of("decision", "APPROVED_BY_ADJUSTER",
                "adjusterId", "ADJ-001", "finalRepairCost", 2600, "repairCostSource", "BODY_SHOP_ESTIMATE")))
                .andExpect(status().isOk());
        assertThat(latestRow(claimId).costSource).isEqualTo("BODY_SHOP_ESTIMATE");
        assertThat(latestRow(claimId).finalRepairCost).isEqualByComparingTo("2600");
    }

    private AiFeedbackEntity latestRow(String claimId) {
        var rows = feedback.findByClaimIdOrderByDecidedAtAsc(claimId);
        assertThat(rows).isNotEmpty();
        return rows.get(rows.size() - 1);
    }

    private String saveClaim(String claimedAmount) {
        ClaimEntity e = new ClaimEntity();
        e.claimId = "CLM-I-" + UUID.randomUUID().toString().substring(0, 8);
        e.policyNumber = "POL-AUTO-112233";
        e.status = "HUMAN_REVIEW";
        e.incidentDate = LocalDate.now().minusDays(3);
        e.state = "CA";
        e.estimatedDamage = new BigDecimal("4200");
        e.reserve = new BigDecimal("3200");
        e.claimedAmount = claimedAmount == null ? null : new BigDecimal(claimedAmount);
        e.deductible = new BigDecimal("1000");
        e.severityGate = "LOW";
        e.createdAt = Instant.now();
        e.updatedAt = e.createdAt;
        claims.save(e);
        return e.claimId;
    }
}
