package com.guidewire.fnol.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guidewire.fnol.api.persistence.ClaimEntity;
import com.guidewire.fnol.api.persistence.ClaimRepository;
import com.guidewire.fnol.common.Models.*;
import com.guidewire.fnol.enrichment.ClaimCenterClient;
import com.guidewire.fnol.enrichment.PolicyCenterClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.ResourceAccessException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The engine as an extension of the claims system: it reacts to a signed claim.submitted event, analyses the FNOL it
 * reads from the claim, and writes the results back onto that same claim; it never creates a claim of its own.
 */
@SpringBootTest(properties = "fnol.claims-events.secret=" + ClaimEventExtensionTest.SECRET)
@AutoConfigureMockMvc
class ClaimEventExtensionTest {

    static final String SECRET = "test-event-secret";
    // Its own policy: the idempotency guard (policy, loss date, state) is shared with the other tests in this database.
    private static final String POLICY = "POL-AUTO-990001";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ClaimRepository claims;
    @MockBean private PolicyCenterClient policyCenterClient;
    @MockBean private ClaimCenterClient claimCenterClient;

    private java.util.concurrent.atomic.AtomicBoolean recordExistedWhenQueued;

    private void activePolicy() {
        LocalDate start = LocalDate.now().minusMonths(6);
        when(policyCenterClient.getPolicy(POLICY)).thenReturn(new Policy(POLICY, "AUTO", "ACTIVE",
                start, start.plusYears(1), "Avery Johnson", "CA", "2025 Camry", List.of()));
        when(policyCenterClient.getCoverages(POLICY)).thenReturn(List.of(
                new Coverage("COLLISION", new BigDecimal("50000"), new BigDecimal("1000"))));
    }

    private static FNOLPayload fnol(int daysAgo) {
        return new FNOLPayload(POLICY, LocalDate.now().minusDays(daysAgo), "CA",
                "Rear-ended at a light, bumper pushed in.", "Avery Johnson", "01/02/1980", null,
                List.of("mock://photo/normal.jpg"), "WEB", null, null, new BigDecimal("3000"));
    }

    private String event(String type, String claimId) throws Exception {
        return objectMapper.writeValueAsString(new ClaimEvent(UUID.randomUUID().toString(), type, "claims-system",
                Instant.now(), claimId, Map.of("claimId", claimId, "policyNumber", POLICY)));
    }

    private static String sign(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    private org.springframework.test.web.servlet.ResultActions send(String body, String signature) throws Exception {
        var request = post("/api/v1/events/claimcenter").contentType(MediaType.APPLICATION_JSON).content(body);
        return mockMvc.perform(signature == null ? request : request.header(ClaimEventController.SIGNATURE_HEADER, signature));
    }

    private static void await(BooleanSupplier done) throws InterruptedException {
        long until = System.currentTimeMillis() + 20_000;
        while (!done.getAsBoolean()) {
            assertThat(System.currentTimeMillis()).as("waited 20 s for the background analysis").isLessThan(until);
            Thread.sleep(50);
        }
    }

    @Test
    @DisplayName("An unsigned or wrongly signed event is refused (401) and nothing is analysed")
    void badSignatureIsRefused() throws Exception {
        String body = event("claim.submitted", "CLM-2026-0E0001");
        send(body, null).andExpect(status().isUnauthorized());
        send(body, "sha256=" + "0".repeat(64)).andExpect(status().isUnauthorized());
        send(body.replace("CLM-2026-0E0001", "CLM-2026-0E0009"), sign(body)).andExpect(status().isUnauthorized());
        verify(claimCenterClient, never()).getFnol(any());
    }

    @Test
    @DisplayName("Other event types are acknowledged and ignored")
    void otherEventsAreIgnored() throws Exception {
        String body = event("claim.closed", "CLM-2026-0E0002");
        send(body, sign(body)).andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("ignored"));
        verify(claimCenterClient, never()).getFnol(any());
    }

    @Test
    @DisplayName("claim.submitted: the FNOL is read from the claim, analysed, and the results are written back onto that claim; a repeat is ignored")
    void submittedClaimIsAnalysedAndWrittenBack() throws Exception {
        String id = "CLM-2026-0E0003";
        activePolicy();
        when(claimCenterClient.getFnol(id)).thenReturn(fnol(31));
        java.util.concurrent.atomic.AtomicBoolean queued = new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(call -> {
            ClaimPatch sent = call.getArgument(1);
            if (sent.workQueue() != null) {
                queued.set(claims.existsById(id));
            }
            return null;
        }).when(claimCenterClient).patchClaim(eq(id), any());
        recordExistedWhenQueued = queued;

        String body = event("claim.submitted", id);
        send(body, sign(body)).andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("accepted"));
        await(() -> claims.existsById(id));

        ArgumentCaptor<ClaimPatch> patch = ArgumentCaptor.forClass(ClaimPatch.class);
        verify(claimCenterClient, timeout(5000).times(3)).patchClaim(eq(id), patch.capture());
        assertThat(patch.getAllValues().get(0).status()).isNull();   // the claim is already open; the AI does not move it
        assertThat(patch.getAllValues().get(0).reserve()).isNull();  // the AI recommends; it posts no reserve
        assertThat(patch.getAllValues().get(0).estimatedDamage()).isNotNull();
        assertThat(patch.getAllValues().get(0).workQueue()).isNull();   // not on a desk until its record is saved
        assertThat(patch.getAllValues().get(2).workQueue()).isEqualTo(claims.findById(id).orElseThrow().status);
        assertThat(recordExistedWhenQueued.get()).as("the record existed when the claim reached a work queue").isTrue();
        verify(claimCenterClient, never()).createClaim(any());   // the claims system owns the claim

        ClaimEntity stored = claims.findById(id).orElseThrow();
        assertThat(stored.policyNumber).isEqualTo(POLICY);
        assertThat(stored.analysisJson).doesNotContain("Avery").doesNotContain("Rear-ended");

        // At-least-once delivery: the same event again is acknowledged, not analysed twice.
        send(body, sign(body)).andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("duplicate"));
        verify(claimCenterClient, times(1)).getFnol(id);
    }

    @Test
    @DisplayName("A claim analysed late, after an outage: the regulatory clock runs from when the claims system received it")
    void lateAnalysisKeepsTheNoticeDate() throws Exception {
        String id = "CLM-2026-0E0010";
        activePolicy();
        when(claimCenterClient.getFnol(id)).thenReturn(fnol(33));
        Instant received = Instant.now().minus(java.time.Duration.ofDays(10));
        String body = objectMapper.writeValueAsString(new ClaimEvent(UUID.randomUUID().toString(), "claim.submitted", "claims-system",
                received, id, Map.of("claimId", id, "policyNumber", POLICY)));

        send(body, sign(body)).andExpect(status().isAccepted());
        await(() -> claims.existsById(id));

        // California: acknowledge within 15 calendar days of notice, so 5 days are left, not 15.
        LocalDate notice = LocalDate.ofInstant(received, java.time.ZoneId.systemDefault());
        assertThat(claims.findById(id).orElseThrow().acknowledgeDue).isEqualTo(notice.plusDays(15));
    }

    @Test
    @DisplayName("If the FNOL cannot be read, the claim stays in review with a high-priority activity for an adjuster")
    void unreadableFnolFlagsTheClaim() throws Exception {
        String id = "CLM-2026-0E0004";
        when(claimCenterClient.getFnol(id)).thenThrow(new ResourceAccessException("claims system unreachable"));

        String body = event("claim.submitted", id);
        send(body, sign(body)).andExpect(status().isAccepted());

        ArgumentCaptor<CcActivity> activity = ArgumentCaptor.forClass(CcActivity.class);
        verify(claimCenterClient, timeout(10_000)).addActivity(eq(id), activity.capture());
        assertThat(activity.getValue().priority()).isEqualTo("high");
        verify(claimCenterClient).patchClaim(eq(id), eq(new ClaimPatch(null, null, null, "HUMAN_REVIEW")));
    }

    @Test
    @DisplayName("If the analysis fails, the failure is recorded under the claims system's number and an adjuster is told")
    void failedAnalysisIsRecordedAndFlagged() throws Exception {
        String id = "CLM-2026-0E0005";
        when(claimCenterClient.getFnol(id)).thenReturn(fnol(32));
        when(policyCenterClient.getPolicy(POLICY)).thenThrow(new ResourceAccessException("policy system unreachable"));

        String body = event("claim.submitted", id);
        send(body, sign(body)).andExpect(status().isAccepted());
        await(() -> claims.existsById(id));

        ClaimEntity stored = claims.findById(id).orElseThrow();
        assertThat(stored.status).isEqualTo("HUMAN_REVIEW");
        assertThat(stored.ccSyncStatus).isEqualTo("FILED");
        assertThat(stored.failureReason).isNotBlank();
        verify(claimCenterClient, timeout(5000)).addActivity(eq(id), any());
        verify(claimCenterClient, never()).createClaim(any());
    }
}
