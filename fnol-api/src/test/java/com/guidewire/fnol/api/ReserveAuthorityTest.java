package com.guidewire.fnol.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guidewire.fnol.api.persistence.AiFeedbackRepository;
import com.guidewire.fnol.api.persistence.ClaimEntity;
import com.guidewire.fnol.api.persistence.ClaimRepository;
import com.guidewire.fnol.common.Models.ClaimUpdateRequest;
import com.guidewire.fnol.enrichment.ClaimCenterClient;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A reserve above someone's authority waits for a person with enough; nothing is posted until then. */
@SpringBootTest(properties = "fnol.security.users=ADJ-A|ADJUSTER|p|Adjuster A;ADJ-B|ADJUSTER|p|Adjuster B;SEN-1|SENIOR_ADJUSTER|p|Senior One;SUP-1|SUPERVISOR|p|Supervisor One;INS-101|INSPECTOR|p|Inspector A")
@AutoConfigureMockMvc
class ReserveAuthorityTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ClaimRepository claims;
    @Autowired private AiFeedbackRepository feedback;
    @Autowired private AiGovernanceService governance;
    @MockBean private ClaimCenterClient claimCenter;

    private String token(String user) throws Exception {
        JsonNode body = objectMapper.readTree(mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("username", user, "password", "p"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        return "Bearer " + body.get("token").asText();
    }

    private void claim(String id, String reserve) {
        ClaimEntity c = new ClaimEntity();
        c.claimId = id;
        c.policyNumber = "POL-AUTO-112233";
        c.status = "HUMAN_REVIEW";
        c.incidentDate = LocalDate.now().minusDays(5);
        c.state = "CA";
        c.estimatedDamage = new BigDecimal(reserve);
        c.reserve = new BigDecimal(reserve);
        c.ccSyncStatus = "SYNCED";
        c.createdAt = Instant.now();
        c.updatedAt = c.createdAt;
        claims.save(c);
    }

    private ResultActions approve(String id, String who) throws Exception {
        return mockMvc.perform(post("/api/v1/fnol/" + id + "/review").header("Authorization", token(who))
                .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"APPROVED_BY_ADJUSTER\",\"adjusterId\":\"" + who + "\",\"notes\":\"ok\"}"));
    }

    private String holdApproval(String id, String who) throws Exception {
        String body = approve(id, who).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL")).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("approvalId").asText();
    }

    private ResultActions decide(String id, String approval, String who, boolean approve) throws Exception {
        return mockMvc.perform(post("/api/v1/fnol/" + id + "/reserve-approvals/" + approval + "/decide").header("Authorization", token(who))
                .contentType(MediaType.APPLICATION_JSON).content("{\"approve\":" + approve + ",\"note\":\"checked\"}"));
    }

    private boolean chainValid(String id) throws Exception {
        return objectMapper.readTree(mockMvc.perform(get("/api/v1/fnol/" + id + "/audit").header("X-Adjuster-Token", "test-adjuster-token"))
                .andReturn().getResponse().getContentAsString()).get("chainValid").asBoolean();
    }

    @Test
    @DisplayName("Within the limit: the decision goes straight through and posts the reserve")
    void withinLimit() throws Exception {
        String id = "CLM-2026-0B0001";
        claim(id, "8000");
        approve(id, "ADJ-A").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED_BY_ADJUSTER"));
        ArgumentCaptor<ClaimUpdateRequest> sent = ArgumentCaptor.forClass(ClaimUpdateRequest.class);
        verify(claimCenter).updateClaim(eq(id), sent.capture());
        assertThat(sent.getValue().adjustedReserve()).isEqualByComparingTo("8000");
    }

    @Test
    @DisplayName("Over the limit: not rejected, held and routed to someone with authority, audited, in the queue; nothing posted")
    void overLimitIsHeld() throws Exception {
        String id = "CLM-2026-0B0002";
        claim(id, "20000");
        String approval = holdApproval(id, "ADJ-A");
        ClaimEntity stored = claims.findById(id).orElseThrow();
        assertThat(stored.status).isEqualTo("PENDING_APPROVAL");
        assertThat(stored.adjusterDecision).isNull();
        assertThat(feedback.findByClaimIdOrderByDecidedAtAsc(id)).isEmpty();

        ArgumentCaptor<ClaimUpdateRequest> sent = ArgumentCaptor.forClass(ClaimUpdateRequest.class);
        verify(claimCenter).updateClaim(eq(id), sent.capture());
        assertThat(sent.getValue().workQueue()).isEqualTo("PENDING_APPROVAL");
        assertThat(sent.getValue().adjustedReserve()).isNull();
        assertThat(sent.getValue().action()).isEqualTo("RESERVE_APPROVAL_REQUESTED");

        mockMvc.perform(get("/api/v1/fnol/queue").header("Authorization", token("SEN-1")).param("status", "PENDING_APPROVAL").param("size", "100"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[?(@.claimId=='" + id + "')]").isNotEmpty());
        mockMvc.perform(get("/api/v1/fnol/" + id + "/reserve-approvals").header("Authorization", token("SEN-1")))
                .andExpect(jsonPath("$[0].approval.approvalId").value(approval))
                .andExpect(jsonPath("$[0].approval.assignedTo").value("SEN-1"))
                .andExpect(jsonPath("$[0].youCanDecide").value(true));
        assertThat(chainValid(id)).isTrue();
    }

    @Test
    @DisplayName("Refused: your own reserve, another adjuster without authority, an inspector, a service credential")
    void whoCannotApprove() throws Exception {
        String id = "CLM-2026-0B0003";
        claim(id, "20000");
        String approval = holdApproval(id, "ADJ-A");
        decide(id, approval, "ADJ-A", true).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("You cannot approve your own reserve"));
        decide(id, approval, "ADJ-B", true).andExpect(status().isForbidden());
        decide(id, approval, "INS-101", true).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/fnol/" + id + "/reserve-approvals/" + approval + "/decide").header("X-Adjuster-Token", "test-adjuster-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"approve\":true}"))
                .andExpect(status().isForbidden());
        assertThat(claims.findById(id).orElseThrow().status).isEqualTo("PENDING_APPROVAL");

        // Even a supervisor cannot approve their own, though nothing is above their limit: they have no over-limit request to make.
        String seniors = "CLM-2026-0B0004";
        claim(seniors, "75000");
        String own = holdApproval(seniors, "SEN-1");
        decide(seniors, own, "SEN-1", true).andExpect(status().isForbidden());
        decide(seniors, own, "ADJ-A", true).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Approval by the right role completes the decision under the person who made it, posts the reserve; the chain stays valid")
    void approvalCompletes() throws Exception {
        String id = "CLM-2026-0B0005";
        claim(id, "20000");
        String approval = holdApproval(id, "ADJ-A");
        decide(id, approval, "SEN-1", true).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED_BY_ADJUSTER"));
        ClaimEntity stored = claims.findById(id).orElseThrow();
        assertThat(stored.adjusterDecision).isEqualTo("APPROVED_BY_ADJUSTER");
        assertThat(stored.adjusterId).isEqualTo("ADJ-A");
        ArgumentCaptor<ClaimUpdateRequest> sent = ArgumentCaptor.forClass(ClaimUpdateRequest.class);
        verify(claimCenter, atLeastOnce()).updateClaim(eq(id), sent.capture());
        ClaimUpdateRequest last = sent.getValue();
        assertThat(last.adjustedReserve()).isEqualByComparingTo("20000");
        assertThat(last.notes()).contains("approved by SEN-1");
        assertThat(feedback.findByClaimIdOrderByDecidedAtAsc(id)).hasSize(1);
        assertThat(chainValid(id)).isTrue();
        decide(id, approval, "SUP-1", true).andExpect(status().isConflict());
    }

    @Test
    @DisplayName("A decline posts nothing and hands the claim back; the person can decide again")
    void declineHandsBack() throws Exception {
        String id = "CLM-2026-0B0006";
        claim(id, "20000");
        String approval = holdApproval(id, "ADJ-A");
        decide(id, approval, "SUP-1", false).andExpect(status().isOk()).andExpect(jsonPath("$.approval").value("DECLINED"));
        assertThat(claims.findById(id).orElseThrow().status).isEqualTo("HUMAN_REVIEW");
        verify(claimCenter, atLeastOnce()).addActivity(eq(id), any());
        verify(claimCenter, never()).recordDecision(any(), any());
        assertThat(chainValid(id)).isTrue();
    }

    @Test
    @DisplayName("A claim decided elsewhere while held: a late approval or decline is refused and changes nothing")
    void staleApprovalIsRefused() throws Exception {
        String id = "CLM-2026-0B0020";
        claim(id, "20000");
        String approval = holdApproval(id, "ADJ-A");
        ClaimEntity moved = claims.findById(id).orElseThrow();
        moved.status = "APPROVED_BY_ADJUSTER";
        claims.save(moved);

        decide(id, approval, "SUP-1", false).andExpect(status().isConflict());
        decide(id, approval, "SUP-1", true).andExpect(status().isConflict());
        assertThat(claims.findById(id).orElseThrow().status).isEqualTo("APPROVED_BY_ADJUSTER");
        assertThat(chainValid(id)).isTrue();
    }

    @Test
    @DisplayName("If the claims system cannot be told about a hold, it is refused and nothing changes, so no claim is lost to approvers")
    void holdNeedsTheClaimsSystem() throws Exception {
        String id = "CLM-2026-0B0021";
        claim(id, "20000");
        org.mockito.Mockito.doThrow(new IllegalStateException("claims system down")).when(claimCenter).updateClaim(eq(id), any());
        approve(id, "ADJ-A").andExpect(status().isServiceUnavailable());
        assertThat(claims.findById(id).orElseThrow().status).isEqualTo("HUMAN_REVIEW");
    }

    @Test
    @DisplayName("With no table row, approving needs the person's own figure: the AI's is for reference only")
    void noTableRowNeedsAFigure() throws Exception {
        String id = "CLM-2026-0B0022";
        claim(id, "3000");
        ClaimEntity flagged = claims.findById(id).orElseThrow();
        flagged.gateFlags = "RESERVE_TABLE_ENTRY_MISSING";
        claims.save(flagged);

        approve(id, "ADJ-A").andExpect(status().isBadRequest());
        assertThat(claims.findById(id).orElseThrow().status).isEqualTo("HUMAN_REVIEW");
        mockMvc.perform(post("/api/v1/fnol/" + id + "/review").header("Authorization", token("ADJ-A")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVED_BY_ADJUSTER\",\"adjusterId\":\"ADJ-A\",\"adjustedReserve\":3000,\"overrideReason\":\"checked the photos\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("A figure entered on a denial is not posted, so it needs no authority")
    void denialPostsNoFigure() throws Exception {
        String id = "CLM-2026-0B0023";
        claim(id, "3000");
        mockMvc.perform(post("/api/v1/fnol/" + id + "/review").header("Authorization", token("ADJ-A")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"REJECTED_BY_ADJUSTER\",\"adjusterId\":\"ADJ-A\",\"adjustedReserve\":60000,\"overrideReason\":\"typed by mistake\"}"))
                .andExpect(status().isOk());
        ArgumentCaptor<ClaimUpdateRequest> sent = ArgumentCaptor.forClass(ClaimUpdateRequest.class);
        verify(claimCenter).updateClaim(eq(id), sent.capture());
        assertThat(sent.getValue().adjustedReserve()).isNull();
    }

    @Test
    @DisplayName("A held claim still counts against its deadline")
    void heldClaimCountsAsOverdue() {
        ClaimEntity held = new ClaimEntity();
        held.status = "PENDING_APPROVAL";
        held.decisionDue = LocalDate.now().minusDays(2);
        assertThat(governance.deadlineCompliance(java.util.List.of(held))).containsEntry("openDecisionsOverdue", 1L);
    }

    @Test
    @DisplayName("Only the money needs authority: asking for more information or denying a large claim goes through")
    void decisionsPostingNothing() throws Exception {
        String id = "CLM-2026-0B0007";
        claim(id, "60000");
        mockMvc.perform(post("/api/v1/fnol/" + id + "/review").header("Authorization", token("ADJ-A"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"NEEDS_MORE_INFO\",\"adjusterId\":\"ADJ-A\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("NEEDS_MORE_INFO"));
        mockMvc.perform(post("/api/v1/fnol/" + id + "/review").header("Authorization", token("ADJ-A"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"REJECTED_BY_ADJUSTER\",\"adjusterId\":\"ADJ-A\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("A service credential cannot decide at all, so it is no route around the limit")
    void serviceCredentialIsLimited() throws Exception {
        String id = "CLM-2026-0B0008";
        claim(id, "20000");
        mockMvc.perform(post("/api/v1/fnol/" + id + "/review").header("X-Adjuster-Token", "test-adjuster-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"APPROVED_BY_ADJUSTER\",\"adjusterId\":\"BATCH\"}"))
                .andExpect(status().isForbidden());
        assertThat(claims.findById(id).orElseThrow().status).isEqualTo("HUMAN_REVIEW");
    }

    @Test
    @DisplayName("An adjuster who changes the figure needs authority for their figure, not the AI's")
    void authorityFollowsTheFinalFigure() throws Exception {
        String id = "CLM-2026-0B0009";
        claim(id, "20000");
        mockMvc.perform(post("/api/v1/fnol/" + id + "/review").header("Authorization", token("ADJ-A")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVED_BY_ADJUSTER\",\"adjusterId\":\"ADJ-A\",\"adjustedReserve\":9000,\"overrideReason\":\"invoice\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED_BY_ADJUSTER"));
    }
}
