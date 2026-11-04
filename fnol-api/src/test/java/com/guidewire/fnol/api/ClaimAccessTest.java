package com.guidewire.fnol.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guidewire.fnol.api.persistence.ClaimEntity;
import com.guidewire.fnol.api.persistence.ClaimRepository;
import com.guidewire.fnol.api.persistence.InspectionEntity;
import com.guidewire.fnol.api.persistence.InspectionRepository;
import com.guidewire.fnol.enrichment.ClaimCenterClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Object-level authorization: who may see and do what on which claim, and the service credential's limits. */
@SpringBootTest
@AutoConfigureMockMvc
class ClaimAccessTest {

    private static final String SERVICE_TOKEN = "test-adjuster-token";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private ClaimRepository claims;
    @Autowired private InspectionRepository inspections;
    @MockBean private ClaimCenterClient claimCenter;

    private String claim(String segment, String assignedTo) {
        ClaimEntity c = new ClaimEntity();
        c.claimId = "CLM-" + UUID.randomUUID().toString().substring(0, 8);
        c.policyNumber = "POL-AUTO-112233";
        c.status = "HUMAN_REVIEW";
        c.incidentDate = LocalDate.now().minusDays(4);
        c.state = "CA";
        c.estimatedDamage = new BigDecimal("4200");
        c.reserve = new BigDecimal("3200");
        c.severityGate = "MEDIUM";
        c.segment = segment;
        c.assignedTo = assignedTo;
        c.ccSyncStatus = "SYNCED";
        c.createdAt = Instant.now();
        c.updatedAt = c.createdAt;
        claims.save(c);
        return c.claimId;
    }

    private String inspection(String claimId, String inspectorId) {
        InspectionEntity i = new InspectionEntity();
        i.inspectionId = UUID.randomUUID().toString();
        i.claimId = claimId;
        i.inspectorId = inspectorId;
        i.inspectorName = "Inspector";
        i.inspectionType = "FIELD";
        i.reason = "Confirm the damage";
        i.status = "ASSIGNED";
        i.assignedBy = "ADJ-001";
        i.assignedAt = Instant.now();
        i.dueBy = LocalDate.now().plusDays(3);
        i.recommendedPayment = new BigDecimal("3200");
        inspections.save(i);
        return i.inspectionId;
    }

    private String bearer(String user) throws Exception {
        return "Bearer " + mapper.readTree(mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("username", user, "password", "p"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("token").asText();
    }

    @Test
    @DisplayName("Inspector on an assigned claim: the basic view, their own inspection without the reserve figure, and the documents")
    void inspectorOnAssignedClaim() throws Exception {
        String id = claim(null, null);
        inspection(id, "INS-101");
        String body = mockMvc.perform(get("/api/v1/fnol/" + id + "/inspection-view").header("Authorization", bearer("INS-101")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.inspections[0].inspectorId").value("INS-101"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("recommendedPayment").doesNotContain("reserve").doesNotContain("3200");
        String listed = mockMvc.perform(get("/api/v1/fnol/" + id + "/inspections").header("Authorization", bearer("INS-101")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(listed).doesNotContain("recommendedPayment");
        mockMvc.perform(get("/api/v1/fnol/" + id + "/documents").header("Authorization", bearer("INS-101"))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("Inspector on an unassigned claim, or another inspector's: 404 everywhere, the same as a claim that does not exist")
    void inspectorOnOtherClaims() throws Exception {
        String unassigned = claim(null, null);
        String others = claim(null, null);
        inspection(others, "INS-102");
        for (String id : new String[]{unassigned, others, "CLM-DOESNOTEXIST"}) {
            for (String path : new String[]{"/inspection-view", "/inspections", "/documents", "/analysis", "/audit"}) {
                mockMvc.perform(get("/api/v1/fnol/" + id + path).header("Authorization", bearer("INS-101"))).andExpect(status().isNotFound());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/analysis", "/explanation", "/audit", "/audit/pdf", "/claimcenter", "/reserve-approvals", "/upload-requests"})
    @DisplayName("Even on an assigned claim, an inspector gets no AI analysis, reserve, fraud signal, notes or audit trail")
    void inspectorOnInternalData(String path) throws Exception {
        String id = claim(null, null);
        inspection(id, "INS-101");
        mockMvc.perform(get("/api/v1/fnol/" + id + path).header("Authorization", bearer("INS-101"))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("An inspector's status row and queue hold no reserve, and the queue only their own claims")
    void inspectorStatusAndQueue() throws Exception {
        String mine = claim(null, null);
        inspection(mine, "INS-101");
        String others = claim(null, null);
        inspection(others, "INS-102");
        mockMvc.perform(get("/api/v1/fnol/status/" + mine).header("Authorization", bearer("INS-101"))).andExpect(status().isNotFound());
        String queue = mockMvc.perform(get("/api/v1/fnol/queue").param("status", "HUMAN_REVIEW,INSPECTION_ASSIGNED").header("Authorization", bearer("INS-101")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(queue).contains(mine).doesNotContain(others).doesNotContain("3200");
    }

    @Test
    @DisplayName("An inspector cannot reach governance")
    void inspectorOnGovernance() throws Exception {
        mockMvc.perform(get("/api/v1/ai/metrics").header("Authorization", bearer("INS-101"))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/ai/feedback").header("Authorization", bearer("INS-101"))).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("An adjuster reads an ordinary claim, and another adjuster's restricted SIU claim is not visible to them, in the file or the queue")
    void adjusterAccess() throws Exception {
        String ordinary = claim("AUTO_STANDARD", "ADJ-002");
        String restricted = claim("SIU_REVIEW", "ADJ-002");
        mockMvc.perform(get("/api/v1/fnol/status/" + ordinary).header("Authorization", bearer("ADJ-001"))).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/fnol/status/" + restricted).header("Authorization", bearer("ADJ-001"))).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/fnol/status/" + restricted).header("Authorization", bearer("ADJ-002"))).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/fnol/" + restricted + "/review").header("Authorization", bearer("ADJ-001")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"APPROVED_BY_ADJUSTER\",\"adjusterId\":\"ADJ-001\"}")).andExpect(status().isNotFound());
        String queue = mockMvc.perform(get("/api/v1/fnol/queue").param("size", "100").header("Authorization", bearer("ADJ-001")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(queue).contains(ordinary).doesNotContain(restricted);
    }

    @Test
    @DisplayName("A supervisor reads restricted claims and governance")
    void supervisorAccess() throws Exception {
        String restricted = claim("SIU_REVIEW", "ADJ-002");
        mockMvc.perform(get("/api/v1/fnol/status/" + restricted).header("Authorization", bearer("SUP-001"))).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/ai/metrics").header("Authorization", bearer("SUP-001"))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("A service credential cannot approve, reject or make any final decision, even naming an adjuster, and nothing is recorded")
    void serviceCannotDecide() throws Exception {
        String id = claim(null, null);
        for (String decision : new String[]{"APPROVED_BY_ADJUSTER", "REJECTED_BY_ADJUSTER", "CLOSED", "NEEDS_MORE_INFO", "INVESTIGATION_REQUIRED"}) {
            mockMvc.perform(post("/api/v1/fnol/" + id + "/review").header("X-Adjuster-Token", SERVICE_TOKEN).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"decision\":\"" + decision + "\",\"adjusterId\":\"ADJ-001\",\"adjustedReserve\":100,\"overrideReason\":\"x\"}"))
                    .andExpect(status().isForbidden());
        }
        ClaimEntity after = claims.findById(id).orElseThrow();
        assertThat(after.status).isEqualTo("HUMAN_REVIEW");
        assertThat(after.adjusterId).isNull();
        verify(claimCenter, never()).recordDecision(any(), any());
        verify(claimCenter, never()).updateClaim(any(), any());
    }

    @Test
    @DisplayName("A name sent by a service credential establishes nothing: it is recorded as the service credential, and it cannot file an inspector's report")
    void serviceCannotImpersonate() throws Exception {
        String id = claim(null, null);
        String inspectionId = inspection(id, "INS-101");
        mockMvc.perform(post("/api/v1/fnol/" + id + "/inspections/" + inspectionId + "/report").header("X-Adjuster-Token", SERVICE_TOKEN).contentType(MediaType.APPLICATION_JSON)
                .content("{\"inspectorId\":\"INS-101\",\"accidentRelatedAmount\":100,\"priorDamageFound\":false,\"findings\":\"x\",\"recommendation\":\"APPROVE\"}"))
                .andExpect(status().isForbidden());
        String requested = mockMvc.perform(post("/api/v1/fnol/" + id + "/upload-requests").header("X-Adjuster-Token", SERVICE_TOKEN).contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestedBy\":\"ADJ-001\",\"angles\":[\"FRONT\"]}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(requested).doesNotContain("ADJ-001");
        assertThat(claims.findById(id).orElseThrow().adjusterId).isEqualTo("service-credential");
    }

    @Test
    @DisplayName("Permitted service work still runs: reading the queue and a claim")
    void servicePermitted() throws Exception {
        String id = claim(null, null);
        mockMvc.perform(get("/api/v1/fnol/queue").header("X-Adjuster-Token", SERVICE_TOKEN)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/fnol/status/" + id).header("X-Adjuster-Token", SERVICE_TOKEN)).andExpect(status().isOk());
    }
}
