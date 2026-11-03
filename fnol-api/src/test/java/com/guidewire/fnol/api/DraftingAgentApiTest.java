package com.guidewire.fnol.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guidewire.fnol.agents.DraftingModel;
import com.guidewire.fnol.api.persistence.AuditRepository;
import com.guidewire.fnol.api.persistence.ClaimEntity;
import com.guidewire.fnol.api.persistence.ClaimRepository;
import com.guidewire.fnol.common.Models.CcNote;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A draft is only a draft: a person accepts it, it writes nothing else, and both steps are audited. Mock model, no network. */
@SpringBootTest(properties = {"fnol.drafting-agent.enabled=true", "fnol.security.users=ADJ-A|ADJUSTER|p|A;INS-1|INSPECTOR|p|I"})
@AutoConfigureMockMvc
class DraftingAgentApiTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private ClaimRepository claims;
    @Autowired private AuditRepository audit;
    @MockBean private DraftingModel model;
    @MockBean private ClaimCenterClient claimCenter;

    private String claim() {
        ClaimEntity c = new ClaimEntity();
        c.claimId = "CLM-2026-0F" + String.format("%04d", SEQ.incrementAndGet());
        c.policyNumber = "POL-AUTO-112233";
        c.status = "HUMAN_REVIEW";
        c.incidentDate = LocalDate.now().minusDays(5);
        c.state = "CA";
        c.estimatedDamage = new BigDecimal("4200");
        c.reserve = new BigDecimal("3200");
        c.severityGate = "MEDIUM";
        c.acknowledgeDue = LocalDate.now().plusDays(10);
        c.ccSyncStatus = "SYNCED";
        c.createdAt = Instant.now();
        c.updatedAt = c.createdAt;
        claims.save(c);
        return c.claimId;
    }

    private String token(String user) throws Exception {
        JsonNode body = mapper.readTree(mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("username", user, "password", "p")))).andReturn().getResponse().getContentAsString());
        return "Bearer " + body.get("token").asText();
    }

    private JsonNode draft(String id, String kind) throws Exception {
        return mapper.readTree(mockMvc.perform(post("/api/v1/fnol/" + id + "/drafts").header("Authorization", token("ADJ-A"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"" + kind + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private ResultActions accept(String id, String draftId, String who, String text) throws Exception {
        return mockMvc.perform(post("/api/v1/fnol/" + id + "/drafts/" + draftId + "/accept").header("Authorization", token(who))
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("text", text))));
    }

    @Test
    @DisplayName("A draft writes nothing to the claims system or the claim; a person's acceptance, edited, makes it the note, and both are audited")
    void acceptanceIsAudited() throws Exception {
        String id = claim();
        when(model.next(any(), any(), any())).thenReturn(DraftingModel.Turn.finish("Look at the photos first."));
        JsonNode d = draft(id, "ADVISORY_NOTE");
        assertThat(d.get("status").asText()).isEqualTo("DRAFT");
        assertThat(d.get("fromModel").asBoolean()).isTrue();
        verify(claimCenter, never()).addNote(any(), any());
        verify(claimCenter, never()).patchClaim(any(), any());
        verify(claimCenter, never()).updateClaim(any(), any());
        assertThat(claims.findById(id).orElseThrow().status).isEqualTo("HUMAN_REVIEW");

        accept(id, d.get("draftId").asText(), "ADJ-A", "Look at the rear photo first.").andExpect(status().isOk());
        ArgumentCaptor<CcNote> note = ArgumentCaptor.forClass(CcNote.class);
        verify(claimCenter).addNote(eq(id), note.capture());
        assertThat(note.getValue().body()).isEqualTo("Look at the rear photo first.");
        assertThat(audit.findByClaimIdOrderBySeqAscTimestampAsc(id)).extracting(a -> a.agentName).contains("DraftingAgent", "DraftAccepted");
        assertThat(claims.findById(id).orElseThrow().status).isEqualTo("HUMAN_REVIEW");
        assertThat(claims.findById(id).orElseThrow().adjusterDecision).isNull();
    }

    @Test
    @DisplayName("A failing model gives the template, still a draft for a person; the letter template holds no name")
    void fallbackIsStillADraft() throws Exception {
        String id = claim();
        when(model.next(any(), any(), any())).thenThrow(new IllegalStateException("no Gemini key"));
        JsonNode d = draft(id, "ACKNOWLEDGEMENT_LETTER");
        assertThat(d.get("fromModel").asBoolean()).isFalse();
        assertThat(d.get("body").asText()).contains("Dear Policyholder").contains(id);
        assertThat(d.get("status").asText()).isEqualTo("DRAFT");
    }

    @Test
    @DisplayName("Creating or reading a draft needs a credential, and an inspector cannot create one")
    void needsACredential() throws Exception {
        String id = claim();
        mockMvc.perform(post("/api/v1/fnol/" + id + "/drafts").contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"ADVISORY_NOTE\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/fnol/" + id + "/drafts")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/fnol/" + id + "/drafts").header("Authorization", token("INS-1")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"ADVISORY_NOTE\"}")).andExpect(status().isForbidden());
        verify(model, never()).next(any(), any(), any());
    }

    @Test
    @DisplayName("Who may accept: never a service credential or an inspector, never twice, and never with personal data in the text")
    void whoMayAccept() throws Exception {
        String id = claim();
        when(model.next(any(), any(), any())).thenReturn(DraftingModel.Turn.finish("Draft."));
        String draftId = draft(id, "ADVISORY_NOTE").get("draftId").asText();
        mockMvc.perform(post("/api/v1/fnol/" + id + "/drafts/" + draftId + "/accept").header("X-Adjuster-Token", "test-adjuster-token")
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        accept(id, draftId, "INS-1", "Fine.").andExpect(status().isForbidden());
        accept(id, draftId, "ADJ-A", "Call them on 415-555-0100.").andExpect(status().isBadRequest());
        accept(id, draftId, "ADJ-A", "Fine.").andExpect(status().isOk());
        accept(id, draftId, "ADJ-A", "Again.").andExpect(status().isConflict());
    }
}
