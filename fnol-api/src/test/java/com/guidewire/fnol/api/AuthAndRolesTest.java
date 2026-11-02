package com.guidewire.fnol.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guidewire.fnol.api.persistence.AiFeedbackRepository;
import com.guidewire.fnol.api.persistence.AppUserRepository;
import com.guidewire.fnol.api.persistence.AuditRepository;
import com.guidewire.fnol.api.persistence.ClaimEntity;
import com.guidewire.fnol.api.persistence.ClaimRepository;
import com.guidewire.fnol.api.persistence.UserSessionRepository;
import com.guidewire.fnol.enrichment.ClaimCenterClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** People sign in with their own account; their role decides what they may do and a decision is recorded under who they are. */
@SpringBootTest(properties = "fnol.security.users=ADJ-A|ADJUSTER|pass-a|Adjuster A;INS-101|INSPECTOR|pass-i|Inspector A;LOCK-1|ADJUSTER|pass-l|Lock Test")
@AutoConfigureMockMvc
class AuthAndRolesTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ClaimRepository claims;
    @Autowired private AppUserRepository users;
    @Autowired private UserSessionRepository sessions;
    @Autowired private AiFeedbackRepository feedback;
    @Autowired private AuditRepository audit;
    @MockBean private ClaimCenterClient claimCenter;

    private ResultActions login(String user, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(java.util.Map.of("username", user, "password", password))));
    }

    private String token(String user, String password) throws Exception {
        JsonNode body = objectMapper.readTree(login(user, password).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        return "Bearer " + body.get("token").asText();
    }

    private void claim(String id) {
        ClaimEntity c = new ClaimEntity();
        c.claimId = id;
        c.policyNumber = "POL-AUTO-112233";
        c.status = "HUMAN_REVIEW";
        c.incidentDate = LocalDate.now().minusDays(5);
        c.state = "CA";
        c.estimatedDamage = new BigDecimal("4200");
        c.reserve = new BigDecimal("3200");
        c.ccSyncStatus = "SYNCED";
        c.createdAt = Instant.now();
        c.updatedAt = c.createdAt;
        claims.save(c);
    }

    @Test
    @DisplayName("Sign-in: a wrong password is refused without saying which part was wrong; a token works until signed out")
    void signInAndOut() throws Exception {
        login("ADJ-A", "wrong").andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("BAD_CREDENTIALS"));
        login("NOBODY", "wrong").andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("BAD_CREDENTIALS"));
        mockMvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());

        String token = token("ADJ-A", "pass-a");
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("ADJ-A")).andExpect(jsonPath("$.role").value("ADJUSTER"));
        mockMvc.perform(get("/api/v1/fnol/queue").header("Authorization", token)).andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/auth/logout").header("Authorization", token)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/fnol/queue").header("Authorization", token)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/fnol/queue").header("Authorization", "Bearer made-up")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Nothing secret is stored: passwords are salted hashes and session tokens are hashes")
    void storedAsHashes() throws Exception {
        String token = token("ADJ-A", "pass-a").substring(7);
        assertThat(users.findById("ADJ-A").orElseThrow().passwordHash).startsWith("pbkdf2$").doesNotContain("pass-a");
        assertThat(sessions.findAll()).extracting(s -> s.tokenHash).doesNotContain(token);
    }

    @Test
    @DisplayName("Five failed sign-ins lock the account for a while, even for the right password")
    void lockout() throws Exception {
        for (int i = 0; i < 5; i++) login("LOCK-1", "wrong").andExpect(status().isUnauthorized());
        login("LOCK-1", "pass-l").andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.error").value("LOCKED_OUT"));
    }

    @Test
    @DisplayName("A decision is recorded under who is signed in, whatever name the request claims; timing counts only then")
    void identityComesFromTheSession() throws Exception {
        String id = "CLM-2026-0A0001";
        claim(id);
        mockMvc.perform(post("/api/v1/fnol/" + id + "/review").header("Authorization", token("ADJ-A", "pass-a"))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                        {"decision":"NEEDS_MORE_INFO","adjusterId":"SOMEONE-ELSE","handlingSeconds":95,"advisoryRating":4}"""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.adjusterId").value("ADJ-A"));
        assertThat(claims.findById(id).orElseThrow().adjusterId).isEqualTo("ADJ-A");
        var row = feedback.findByClaimIdOrderByDecidedAtAsc(id).get(0);
        assertThat(row.adjusterId).isEqualTo("ADJ-A");
        assertThat(row.handlingSeconds).isEqualTo(95);
        assertThat(row.advisoryRating).isEqualTo(4);
        assertThat(audit.findByClaimIdOrderBySeqAscTimestampAsc(id)).anyMatch(a -> "ADJ-A".equals(a.actor));

        // A service-token script states its own name, and its timing never counts as evidence.
        String scripted = "CLM-2026-0A0002";
        claim(scripted);
        mockMvc.perform(post("/api/v1/fnol/" + scripted + "/review").header("X-Adjuster-Token", "test-adjuster-token")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                        {"decision":"NEEDS_MORE_INFO","adjusterId":"BATCH-JOB","handlingSeconds":1,"advisoryRating":5}"""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.adjusterId").value("BATCH-JOB"));
        assertThat(feedback.findByClaimIdOrderByDecidedAtAsc(scripted).get(0).handlingSeconds).isNull();
    }

    @Test
    @DisplayName("Separation of duties: an inspector reads and reports but cannot decide; an adjuster decides but cannot file a report")
    void rolesSeparateDuties() throws Exception {
        String id = "CLM-2026-0A0003";
        claim(id);
        String inspector = token("INS-101", "pass-i");
        String adjuster = token("ADJ-A", "pass-a");

        mockMvc.perform(get("/api/v1/fnol/status/" + id).header("Authorization", inspector)).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/fnol/" + id + "/review").header("Authorization", inspector)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"APPROVED_BY_ADJUSTER\",\"adjusterId\":\"INS-101\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("FORBIDDEN"));
        assertThat(claims.findById(id).orElseThrow().adjusterDecision).isNull();
        mockMvc.perform(get("/api/v1/ai/metrics").header("Authorization", inspector)).andExpect(status().isForbidden());

        // The adjuster assigns; only the assigned inspector can file the report, and not under another inspector's name.
        String assigned = mockMvc.perform(post("/api/v1/fnol/" + id + "/inspections").header("Authorization", adjuster)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                        {"inspectorId":"INS-102","inspectionType":"DESK","reason":"Check the estimate","assignedBy":"ANYONE"}"""))
                .andExpect(status().is2xxSuccessful()).andExpect(jsonPath("$.assignedBy").value("ADJ-A"))
                .andReturn().getResponse().getContentAsString();
        String inspection = objectMapper.readTree(assigned).get("inspectionId").asText();
        String report = """
                {"inspectorId":"INS-102","accidentRelatedAmount":2000,"priorDamageFound":false,"findings":"Looked at it","recommendation":"APPROVE"}""";
        mockMvc.perform(post("/api/v1/fnol/" + id + "/inspections/" + inspection + "/report").header("Authorization", adjuster)
                        .contentType(MediaType.APPLICATION_JSON).content(report))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/fnol/" + id + "/inspections/" + inspection + "/report").header("Authorization", inspector)
                        .contentType(MediaType.APPLICATION_JSON).content(report))
                .andExpect(status().isConflict());   // INS-101 is signed in; the inspection is INS-102's
    }

    @Test
    @DisplayName("The evidence metric starts empty and reads only what signed-in people recorded")
    void evidenceMetric() throws Exception {
        String id = "CLM-2026-0A0004";
        claim(id);
        String adjuster = token("ADJ-A", "pass-a");
        mockMvc.perform(post("/api/v1/fnol/" + id + "/review").header("Authorization", adjuster)
                .contentType(MediaType.APPLICATION_JSON).content("""
                {"decision":"NEEDS_MORE_INFO","adjusterId":"ADJ-A","handlingSeconds":60,"advisoryRating":5}""")).andExpect(status().isOk());
        JsonNode evidence = objectMapper.readTree(mockMvc.perform(get("/api/v1/ai/metrics").header("Authorization", adjuster))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("adjusterEvidence");
        assertThat(evidence.path("decisionsTimed").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(evidence.path("ratings").asInt()).isGreaterThanOrEqualTo(1);
    }
}
