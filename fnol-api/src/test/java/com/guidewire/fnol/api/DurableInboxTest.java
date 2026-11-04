package com.guidewire.fnol.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guidewire.fnol.agents.VisionProvider;
import com.guidewire.fnol.api.persistence.ClaimRepository;
import com.guidewire.fnol.api.persistence.EventInboxEntity;
import com.guidewire.fnol.api.persistence.EventInboxRepository;
import com.guidewire.fnol.common.Models.ClaimEvent;
import com.guidewire.fnol.common.Models.ClaimPatch;
import com.guidewire.fnol.common.Models.Coverage;
import com.guidewire.fnol.common.Models.FNOLPayload;
import com.guidewire.fnol.common.Models.Policy;
import com.guidewire.fnol.enrichment.ClaimCenterClient;
import com.guidewire.fnol.enrichment.PolicyCenterClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.client.ResourceAccessException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A 202 means durably accepted. The scheduled worker is off here, so each test drives exactly what would run. */
@SpringBootTest(properties = {"fnol.claims-events.secret=" + DurableInboxTest.SECRET, "fnol.inbox.worker-enabled=false",
        "fnol.inbox.max-attempts=3", "fnol.inbox.backoff-base-seconds=0", "fnol.inbox.backoff-max-seconds=0"})
@AutoConfigureMockMvc
class DurableInboxTest {

    static final String SECRET = "test-event-secret";
    private static final String POLICY = "POL-AUTO-990002";
    private static final AtomicInteger DAYS = new AtomicInteger(1);

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private EventInboxRepository inbox;
    @Autowired private ClaimRepository claims;
    @Autowired private EventInboxWorker worker;
    @Autowired private EventInboxService service;
    @MockBean private ClaimCenterClient claimCenter;
    @MockBean private PolicyCenterClient policyCenter;
    @SpyBean private VisionProvider vision;

    private String claim() {
        String id = "CLM-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        LocalDate start = LocalDate.now().minusMonths(6);
        when(policyCenter.getPolicy(POLICY)).thenReturn(new Policy(POLICY, "AUTO", "ACTIVE", start, start.plusYears(1), "Avery Johnson", "CA", "2025 Camry", List.of()));
        when(policyCenter.getCoverages(POLICY)).thenReturn(List.of(new Coverage("COLLISION", new BigDecimal("50000"), new BigDecimal("1000"))));
        when(claimCenter.getFnol(id)).thenReturn(new FNOLPayload(POLICY, LocalDate.now().minusDays(DAYS.incrementAndGet()), "CA", "Rear-ended at a light.", "Avery Johnson",
                "01/02/1980", null, List.of("mock://photo/normal.jpg"), "WEB", null, null, new BigDecimal("3000")));
        return id;
    }

    private String body(String eventId, String claimId) throws Exception {
        return mapper.writeValueAsString(new ClaimEvent(eventId, "claim.submitted", "claims-system", Instant.now(), claimId, Map.of("claimId", claimId)));
    }

    private ResultActions send(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return mockMvc.perform(post("/api/v1/events/claimcenter").contentType(MediaType.APPLICATION_JSON).content(body)
                .header(ClaimEventController.SIGNATURE_HEADER, "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)))));
    }

    private EventInboxEntity row(String eventId) {
        return inbox.findByEventId(eventId).orElseThrow();
    }

    @Test
    @DisplayName("Scenario A: the 202 is backed by a committed row before any work ran, and a worker started later picks it up")
    void survivesRestart() throws Exception {
        String id = claim();
        String eventId = UUID.randomUUID().toString();
        send(body(eventId, id)).andExpect(status().isAccepted());

        // Nothing has processed it: as if the process died the moment after answering.
        assertThat(row(eventId).status).isEqualTo(EventInboxEntity.PENDING);
        assertThat(claims.existsById(id)).isFalse();

        worker.runOnce("worker-after-restart");
        assertThat(row(eventId).status).isEqualTo(EventInboxEntity.COMPLETED);
        assertThat(claims.existsById(id)).isTrue();
    }

    @Test
    @DisplayName("Scenario B: a retry reuses the branch that finished: vision is not called twice when a later step fails")
    void retryKeepsFinishedWork() throws Exception {
        String id = claim();
        String eventId = UUID.randomUUID().toString();
        // The claims system is down for the write-back, after both branches finished.
        doThrow(new ResourceAccessException("claims system down")).doNothing().when(claimCenter).patchClaim(eq(id), any());
        send(body(eventId, id)).andExpect(status().isAccepted());

        worker.runOnce("worker-1");
        EventInboxEntity first = row(eventId);
        assertThat(first.status).isEqualTo(EventInboxEntity.RETRY_PENDING);
        assertThat(first.branchAJson).isNotNull();
        assertThat(first.branchBJson).isNotNull();

        worker.runOnce("worker-1");
        assertThat(row(eventId).status).isEqualTo(EventInboxEntity.COMPLETED);
        verify(vision, times(1)).assess(any(), any());
        assertThat(claims.existsById(id)).isTrue();
        assertThat(row(eventId).branchAJson).as("cleared once finished").isNull();
        assertThat(row(eventId).branchBJson).isNull();
    }

    @Test
    @DisplayName("Scenario C: a duplicate event creates one workflow; the same id with different content is refused and recorded")
    void duplicates() throws Exception {
        String id = claim();
        String eventId = UUID.randomUUID().toString();
        String body = body(eventId, id);
        send(body).andExpect(status().isAccepted());
        send(body).andExpect(status().isAccepted());
        assertThat(inbox.findAll().stream().filter(e -> eventId.equals(e.eventId))).hasSize(1);

        send(body(eventId, "CLM-OTHER")).andExpect(status().isConflict());
        assertThat(row(eventId).aggregateId).isEqualTo(id);

        worker.runOnce("worker-1");
        verify(claimCenter, times(1)).getFnol(id);
    }

    @Test
    @DisplayName("The same event delivered at the same moment makes one row")
    void concurrentDuplicates() throws Exception {
        String id = claim();
        String eventId = UUID.randomUUID().toString();
        String body = body(eventId, id);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Callable<Integer>> sends = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            sends.add(() -> send(body).andReturn().getResponse().getStatus());
        }
        for (Future<Integer> f : pool.invokeAll(sends)) {
            assertThat(f.get()).isEqualTo(202);
        }
        pool.shutdown();
        assertThat(inbox.findAll().stream().filter(e -> eventId.equals(e.eventId))).hasSize(1);
    }

    @Test
    @DisplayName("Scenario D: a worker that died holding a lease loses it when it runs out, and another safely takes the job; a live lease is left alone")
    void leaseRecovery() throws Exception {
        String id = claim();
        String eventId = UUID.randomUUID().toString();
        send(body(eventId, id)).andExpect(status().isAccepted());
        assertThat(service.claimDue("worker-that-dies", 10)).extracting(e -> e.eventId).contains(eventId);
        assertThat(service.claimDue("second-worker", 10)).extracting(e -> e.eventId).as("the lease is live").doesNotContain(eventId);

        EventInboxEntity held = row(eventId);
        held.leaseExpiresAt = Instant.now().minusSeconds(1);
        inbox.save(held);

        worker.runOnce("second-worker");
        assertThat(row(eventId).status).isEqualTo(EventInboxEntity.COMPLETED);
        assertThat(row(eventId).attemptCount).isEqualTo(2);
        assertThat(claims.existsById(id)).isTrue();
    }

    @Test
    @DisplayName("Scenario E: with the claims system down the analysis is kept, and the write-back succeeds when it returns")
    void claimsSystemOutage() throws Exception {
        String id = claim();
        String eventId = UUID.randomUUID().toString();
        when(claimCenter.getFnol(id)).thenThrow(new ResourceAccessException("down")).thenReturn(new FNOLPayload(POLICY, LocalDate.now().minusDays(DAYS.incrementAndGet()), "CA",
                "Rear-ended at a light.", "Avery Johnson", "01/02/1980", null, List.of("mock://photo/normal.jpg"), "WEB", null, null, new BigDecimal("3000")));
        send(body(eventId, id)).andExpect(status().isAccepted());
        worker.runOnce("worker-1");
        assertThat(row(eventId).status).isEqualTo(EventInboxEntity.RETRY_PENDING);
        assertThat(row(eventId).lastError).contains("down");
        worker.runOnce("worker-1");
        assertThat(row(eventId).status).isEqualTo(EventInboxEntity.COMPLETED);
        assertThat(claims.existsById(id)).isTrue();
    }

    @Test
    @DisplayName("Attempts used up: dead letter, an adjuster is told, and a supervisor can replay it; an adjuster cannot")
    void deadLetterAndReplay() throws Exception {
        String id = claim();
        String eventId = UUID.randomUUID().toString();
        when(claimCenter.getFnol(id)).thenThrow(new ResourceAccessException("down"));
        send(body(eventId, id)).andExpect(status().isAccepted());
        for (int i = 0; i < 3; i++) {
            worker.runOnce("worker-1");
        }
        EventInboxEntity dead = row(eventId);
        assertThat(dead.status).isEqualTo(EventInboxEntity.DEAD_LETTER);
        verify(claimCenter).addActivity(eq(id), any());
        verify(claimCenter).patchClaim(eq(id), eq(new ClaimPatch(null, null, null, "HUMAN_REVIEW")));

        mockMvc.perform(post("/api/v1/admin/event-inbox/" + dead.id + "/replay").header("Authorization", HumanSession.bearer(mockMvc, "ADJ-001"))).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/admin/event-inbox/" + dead.id + "/replay").header("X-Adjuster-Token", "test-adjuster-token")).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/event-inbox").header("Authorization", HumanSession.bearer(mockMvc, "SUP-001"))).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/admin/event-inbox/" + dead.id + "/replay").header("Authorization", HumanSession.bearer(mockMvc, "SUP-001"))).andExpect(status().isAccepted());
        assertThat(row(eventId).status).isEqualTo(EventInboxEntity.RETRY_PENDING);
        assertThat(row(eventId).attemptCount).isZero();
    }

    @Test
    @DisplayName("A failing analysis reaches a person on the first failure, is flagged once, and a later success replaces the failure")
    void firstFailureTellsAPerson() throws Exception {
        String id = claim();
        String eventId = UUID.randomUUID().toString();
        doThrow(new ResourceAccessException("model away")).doCallRealMethod().when(vision).assess(any(FNOLPayload.class), anyString());
        send(body(eventId, id)).andExpect(status().isAccepted());

        worker.runOnce("worker-1");

        assertThat(row(eventId).status).isEqualTo(EventInboxEntity.RETRY_PENDING);
        assertThat(claims.findById(id).orElseThrow().failureReason).contains("Retrying automatically");
        assertThat(claims.findById(id).orElseThrow().status).isEqualTo("HUMAN_REVIEW");
        verify(claimCenter).addActivity(eq(id), any());
        verify(claimCenter).patchClaim(eq(id), eq(new ClaimPatch(null, null, null, "HUMAN_REVIEW")));

        worker.runOnce("worker-1");

        assertThat(row(eventId).status).isEqualTo(EventInboxEntity.COMPLETED);
        assertThat(claims.findById(id).orElseThrow().failureReason).isNull();
        assertThat(claims.findById(id).orElseThrow().analysisJson).isNotNull();
    }

    @Test
    @DisplayName("A worker that died before telling anyone: the next attempt to fail tells a person, though it is not the first attempt")
    void failureAfterACrashStillTellsAPerson() throws Exception {
        String id = claim();
        String eventId = UUID.randomUUID().toString();
        doThrow(new ResourceAccessException("model away")).when(vision).assess(any(FNOLPayload.class), anyString());
        send(body(eventId, id)).andExpect(status().isAccepted());
        EventInboxEntity died = row(eventId);
        died.status = EventInboxEntity.PROCESSING;
        died.attemptCount = 1;
        died.leaseOwner = "worker-that-died";
        died.leaseExpiresAt = Instant.now().minusSeconds(1);
        inbox.save(died);
        assertThat(claims.existsById(id)).isFalse();

        worker.runOnce("worker-2");

        assertThat(row(eventId).attemptCount).isEqualTo(2);
        assertThat(row(eventId).status).isEqualTo(EventInboxEntity.RETRY_PENDING);
        assertThat(claims.findById(id).orElseThrow().status).isEqualTo("HUMAN_REVIEW");
        verify(claimCenter).addActivity(eq(id), any());

        worker.runOnce("worker-2");

        assertThat(row(eventId).status).isEqualTo(EventInboxEntity.DEAD_LETTER);
    }

    @Test
    @DisplayName("A person who decided while the analysis was retrying is not overwritten by the retry")
    void retryDoesNotOverwriteADecision() throws Exception {
        String id = claim();
        String eventId = UUID.randomUUID().toString();
        doThrow(new ResourceAccessException("model away")).doCallRealMethod().when(vision).assess(any(FNOLPayload.class), anyString());
        send(body(eventId, id)).andExpect(status().isAccepted());
        worker.runOnce("worker-1");
        var flagged = claims.findById(id).orElseThrow();
        flagged.adjusterDecision = "REJECTED_BY_ADJUSTER";
        flagged.status = "REJECTED_BY_ADJUSTER";
        claims.save(flagged);

        worker.runOnce("worker-1");

        assertThat(row(eventId).status).isEqualTo(EventInboxEntity.COMPLETED);
        assertThat(claims.findById(id).orElseThrow().adjusterDecision).isEqualTo("REJECTED_BY_ADJUSTER");
        assertThat(claims.findById(id).orElseThrow().status).isEqualTo("REJECTED_BY_ADJUSTER");
    }

    @Test
    @DisplayName("A permanent failure is not retried: it goes straight to an adjuster")
    void permanentFailureNotRetried() throws Exception {
        String id = claim();
        String eventId = UUID.randomUUID().toString();
        when(policyCenter.getPolicy(POLICY)).thenReturn(null);
        send(body(eventId, id)).andExpect(status().isAccepted());
        worker.runOnce("worker-1");
        assertThat(row(eventId).status).isEqualTo(EventInboxEntity.COMPLETED);
        assertThat(row(eventId).attemptCount).isEqualTo(1);
        verify(claimCenter).addActivity(eq(id), any());
    }

    @Test
    @DisplayName("An unsigned or malformed event is refused before anything is stored")
    void refusedEvents() throws Exception {
        long before = inbox.count();
        mockMvc.perform(post("/api/v1/events/claimcenter").contentType(MediaType.APPLICATION_JSON).content(body("x", "CLM-1"))).andExpect(status().isUnauthorized());
        send("{\"type\":\"claim.submitted\",\"subject\":\"CLM-1\"}").andExpect(status().isBadRequest());
        send("not json").andExpect(status().isBadRequest());
        assertThat(inbox.count()).isEqualTo(before);
    }

    @Test
    @DisplayName("Backoff doubles, has jitter and a ceiling; the hash ignores field order")
    void backoffAndHash() {
        Duration base = Duration.ofSeconds(5);
        Duration max = Duration.ofSeconds(60);
        assertThat(EventInboxService.backoff(1, base, max).toMillis()).isBetween(2500L, 5000L);
        assertThat(EventInboxService.backoff(3, base, max).toMillis()).isBetween(10_000L, 20_000L);
        assertThat(EventInboxService.backoff(30, base, max).toMillis()).isLessThanOrEqualTo(60_000L);
        assertThat(EventInboxService.canonicalHash("{\"a\":1,\"b\":{\"c\":2,\"d\":3}}")).isEqualTo(EventInboxService.canonicalHash("{\"b\":{\"d\":3,\"c\":2},\"a\":1}"));
        assertThat(EventInboxService.canonicalHash("{\"a\":1}")).isNotEqualTo(EventInboxService.canonicalHash("{\"a\":2}"));
    }

    @Test
    @DisplayName("Two workers racing never hold the same job")
    void concurrentClaims() throws Exception {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String eventId = UUID.randomUUID().toString();
            ids.add(eventId);
            send(body(eventId, claim())).andExpect(status().isAccepted());
        }
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<List<EventInboxEntity>> a = pool.submit(() -> service.claimDue("a", 8));
        Future<List<EventInboxEntity>> b = pool.submit(() -> service.claimDue("b", 8));
        List<String> taken = new ArrayList<>();
        a.get().forEach(e -> taken.add(e.eventId));
        b.get().forEach(e -> taken.add(e.eventId));
        pool.shutdown();
        assertThat(taken).doesNotHaveDuplicates();
    }
}
