package com.guidewire.fnol.api;

import com.guidewire.fnol.api.persistence.AuditRepository;
import com.guidewire.fnol.api.persistence.ClaimEntity;
import com.guidewire.fnol.api.persistence.ClaimRepository;
import com.guidewire.fnol.common.Models.CcActivity;
import com.guidewire.fnol.common.Models.ClaimPatch;
import com.guidewire.fnol.enrichment.ClaimCenterClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Who handles a claim: a segment from rules kept as data, then the least busy person in that segment's group. */
@SpringBootTest(properties = "fnol.security.users=ADJ-A|ADJUSTER|p|A;ADJ-B|ADJUSTER|p|B;SEN-1|SENIOR_ADJUSTER|p|S1;SEN-2|SENIOR_ADJUSTER|p|S2;SUP-1|SUPERVISOR|p|Sup;INS-101|INSPECTOR|p|I")
@AutoConfigureMockMvc
class SegmentationAndAssignmentTest {

    @Autowired private ClaimAssignmentService assignment;
    @Autowired private ClaimRepository claims;
    @Autowired private AuditRepository audit;
    @Autowired private MockMvc mockMvc;
    @MockBean private ClaimCenterClient claimCenter;
    private int seq;

    private final ClaimSegmenter segmenter = new ClaimSegmenter();

    @ParameterizedTest(name = "{0} {1} {2} ${3} is {4}")
    @CsvSource(nullValues = "-", value = {
            "AUTO, LOW, FRAUD_ANOMALY, 1000, SIU_REVIEW",
            "HOME, LOW, FRAUD_ANOMALY, 1000, SIU_REVIEW",
            "AUTO, LOW, ATTORNEY_REPRESENTED, 1000, AUTO_COMPLEX",
            "AUTO, LOW, HIGH_CLAIM_FREQUENCY, 1000, AUTO_COMPLEX",
            "AUTO, HIGH, -, 1000, AUTO_COMPLEX",
            "AUTO, MEDIUM, -, 25000, AUTO_COMPLEX",
            "AUTO, LOW, -, 3000, AUTO_FAST_TRACK",
            "AUTO, LOW, -, 6000, AUTO_STANDARD",
            "AUTO, MEDIUM, -, 3000, AUTO_STANDARD",
            "AUTO, LOW, SUBROGATION, 3000, AUTO_STANDARD",
            "AUTO, LOW, MISSING_EVIDENCE, 3000, AUTO_STANDARD",
            "HOME, LOW, ATTORNEY_REPRESENTED, 1000, PROPERTY_COMPLEX",
            "HOME, MEDIUM, POSSIBLE_FLOOD_EXCLUSION, 1000, PROPERTY_COMPLEX",
            "HOME, HIGH, -, 1000, PROPERTY_COMPLEX",
            "HOME, MEDIUM, -, 25000, PROPERTY_COMPLEX",
            "HOME, LOW, -, 1000, PROPERTY_STANDARD",
            "OTHER, LOW, -, 1000, AUTO_STANDARD",
    })
    @DisplayName("Each segment rule")
    void segmentRules(String line, String severity, String flag, String reserve, String expected) {
        assertThat(segmenter.segment(new ClaimSegmenter.Input(line, severity, flag == null ? Set.of() : Set.of(flag), new BigDecimal(reserve))).segment())
                .isEqualTo(expected);
    }

    @Test
    @DisplayName("Every segment names a group with roles that exist")
    void everySegmentHasAGroup() {
        for (String line : List.of("AUTO", "HOME")) {
            ClaimSegmenter.Segment s = segmenter.segment(new ClaimSegmenter.Input(line, "LOW", Set.of(), BigDecimal.ONE));
            assertThat(s.group()).isNotBlank();
            assertThat(s.roles()).isNotEmpty();
        }
    }

    private String claim(String status) {
        ClaimEntity c = new ClaimEntity();
        c.claimId = "CLM-2026-" + String.format("0S%04d", ++seq);
        c.policyNumber = "POL-AUTO-112233";
        c.status = status;
        c.incidentDate = LocalDate.now().minusDays(5);
        c.state = "CA";
        c.estimatedDamage = new BigDecimal("4200");
        c.reserve = new BigDecimal("3200");
        c.ccSyncStatus = "SYNCED";
        c.createdAt = Instant.now();
        c.updatedAt = c.createdAt;
        claims.save(c);
        return c.claimId;
    }

    private String assign(String reserve) {
        String id = claim("HUMAN_REVIEW");
        assignment.assign(id, new ClaimSegmenter.Input("AUTO", "LOW", Set.of(), new BigDecimal(reserve)));
        return id;
    }

    @Test
    @DisplayName("Balancing: the group's work spreads evenly, round robin on a tie, and a closed claim frees someone up")
    void balancing() {
        List<String> who = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            String id = assign("3000");   // AUTO_FAST_TRACK: the two adjusters
            ids.add(id);
            who.add(claims.findById(id).orElseThrow().assignedTo);
        }
        assertThat(who.get(0)).isNotEqualTo(who.get(1));
        assertThat(who.get(2)).isNotEqualTo(who.get(3));
        assertThat(who).containsOnly("ADJ-A", "ADJ-B");
        assertThat(who.stream().filter("ADJ-A"::equals).count()).isEqualTo(2);

        // One of them finishes a claim: they now have the fewest, so they get the next one.
        ClaimEntity done = claims.findById(ids.get(0)).orElseThrow();
        done.status = "CLOSED";
        claims.save(done);
        assertThat(claims.findById(assign("3000")).orElseThrow().assignedTo).isEqualTo(who.get(0));
    }

    @Test
    @DisplayName("The assignment reaches the claims system, is audited, and never goes to an inspector")
    void wiring() {
        String id = assign("3000");
        ClaimEntity c = claims.findById(id).orElseThrow();
        assertThat(c.segment).isEqualTo("AUTO_FAST_TRACK");
        assertThat(c.assignmentGroup).isEqualTo("Auto fast-track queue");
        assertThat(c.assignedTo).isNotEqualTo("INS-101");
        ArgumentCaptor<ClaimPatch> patch = ArgumentCaptor.forClass(ClaimPatch.class);
        verify(claimCenter).patchClaim(eq(id), patch.capture());
        assertThat(patch.getValue().segment()).isEqualTo("AUTO_FAST_TRACK");
        assertThat(patch.getValue().assignedTo()).isEqualTo(c.assignedTo);
        assertThat(patch.getValue().status()).isNull();
        assertThat(patch.getValue().reserve()).isNull();    // assignment never posts money
        assertThat(audit.findByClaimIdOrderBySeqAscTimestampAsc(id))
                .anyMatch(a -> "SegmentationAndAssignment".equals(a.agentName));
        verify(claimCenter, never()).addActivity(eq(id), any());
    }

    @Test
    @DisplayName("A reserve above everyone in the group is assigned and also routed to a supervisor; within someone's limit it is not")
    void aboveTheGroup() {
        String id = claim("HUMAN_REVIEW");
        assignment.assign(id, new ClaimSegmenter.Input("AUTO", "LOW", Set.of("SUBROGATION"), new BigDecimal("15000")));
        assertThat(claims.findById(id).orElseThrow().segment).isEqualTo("AUTO_STANDARD");
        verify(claimCenter, never()).addActivity(eq(id), any());   // a senior adjuster in the group has enough authority

        String huge = claim("HUMAN_REVIEW");
        assignmentAboveEveryone(huge);
        assertThat(claims.findById(huge).orElseThrow().assignedTo).isIn("ADJ-A", "ADJ-B");
        ArgumentCaptor<CcActivity> activity = ArgumentCaptor.forClass(CcActivity.class);
        verify(claimCenter).addActivity(eq(huge), activity.capture());
        assertThat(activity.getValue().subject()).contains("above the assigned group's authority");
        assertThat(activity.getValue().assignedTo()).isEqualTo("SUP-1");
    }

    @Test
    @DisplayName("A group with nobody in it says so, rather than claiming the reserve is above everyone's authority")
    void emptyGroupSaysSo() throws Exception {
        String id = claim("HUMAN_REVIEW");
        com.fasterxml.jackson.databind.JsonNode rules = new com.fasterxml.jackson.databind.ObjectMapper().readTree("""
                {"rules":[{"segment":"EMPTY","why":"test"}],"groups":{"EMPTY":{"group":"Empty queue","roles":["NO_SUCH_ROLE"]}}}""");
        new ClaimAssignmentService(new ClaimSegmenter(rules), usersRepository, claims, authority, auditService, auditAgent, claimCenter)
                .assign(id, new ClaimSegmenter.Input("AUTO", "LOW", Set.of(), new BigDecimal("500")));
        assertThat(claims.findById(id).orElseThrow().assignedTo).isNull();
        ArgumentCaptor<CcActivity> activity = ArgumentCaptor.forClass(CcActivity.class);
        verify(claimCenter).addActivity(eq(id), activity.capture());
        assertThat(activity.getValue().subject()).isEqualTo("No one is available to take this claim");
    }

    @Test
    @DisplayName("Assignment writes only its own columns: a decision made meanwhile is not overwritten")
    void assignmentKeepsADecision() {
        String id = claim("APPROVED_BY_ADJUSTER");
        assignment.assign(id, new ClaimSegmenter.Input("AUTO", "LOW", Set.of(), new BigDecimal("3000")));
        ClaimEntity c = claims.findById(id).orElseThrow();
        assertThat(c.status).isEqualTo("APPROVED_BY_ADJUSTER");
        assertThat(c.segment).isEqualTo("AUTO_FAST_TRACK");
    }

    /** Rules with a group of adjusters only, so a large reserve is above everyone in it. */
    private void assignmentAboveEveryone(String id) {
        try {
            com.fasterxml.jackson.databind.JsonNode rules = new com.fasterxml.jackson.databind.ObjectMapper().readTree("""
                    {"rules":[{"segment":"NARROW","why":"test"}],"groups":{"NARROW":{"group":"Narrow queue","roles":["ADJUSTER"]}}}""");
            new ClaimAssignmentService(new ClaimSegmenter(rules), usersRepository, claims, authority, auditService, auditAgent, claimCenter)
                    .assign(id, new ClaimSegmenter.Input("AUTO", "LOW", Set.of(), new BigDecimal("60000")));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired private com.guidewire.fnol.api.persistence.AppUserRepository usersRepository;
    @Autowired private com.guidewire.fnol.api.security.ReserveAuthority authority;
    @Autowired private AuditService auditService;
    @Autowired private com.guidewire.fnol.agents.AuditTrailAgent auditAgent;

    @Test
    @DisplayName("Queue filter: by segment, showing who it is assigned to")
    void queueBySegment() throws Exception {
        String id = assign("3000");
        String other = claim("HUMAN_REVIEW");
        assignment.assign(other, new ClaimSegmenter.Input("AUTO", "HIGH", Set.of(), new BigDecimal("3000")));
        mockMvc.perform(get("/api/v1/fnol/queue").header("X-Adjuster-Token", "test-adjuster-token")
                        .param("status", "HUMAN_REVIEW").param("segment", "AUTO_COMPLEX").param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.claimId=='" + other + "')].assignedTo").isNotEmpty())
                .andExpect(jsonPath("$.items[?(@.claimId=='" + id + "')]").isEmpty());
    }

    @Test
    @DisplayName("A failure to assign never fails the claim: it stays in its queue, unassigned")
    void failureIsSafe() {
        assignment.assign("CLM-2026-NOSUCH", new ClaimSegmenter.Input("AUTO", "LOW", Set.of(), BigDecimal.ONE));
        org.mockito.Mockito.doThrow(new IllegalStateException("claims system down")).when(claimCenter).patchClaim(any(), any());
        String id = assign("3000");
        assertThat(claims.findById(id).orElseThrow().assignedTo).isNotNull();
    }
}
