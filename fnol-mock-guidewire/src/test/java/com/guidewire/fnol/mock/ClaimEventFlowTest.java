package com.guidewire.fnol.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guidewire.fnol.common.Models.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The claims system as system of record: an FNOL is reported, submitted, and announced to the AI extension. */
class ClaimEventFlowTest {

    private static final String SECRET = "test-event-secret";

    private final ObjectMapper mapper = Jackson2ObjectMapperBuilder.json().build();
    private final List<Map<String, String>> received = new CopyOnWriteArrayList<>();
    private final AtomicInteger reply = new AtomicInteger(500);
    private HttpServer subscriber;
    private JdbcTemplate jdbc;
    private ClaimEventOutbox outbox;
    private ClaimCenterController cc;

    private void start() throws IOException {
        DriverManagerDataSource db = new DriverManagerDataSource(
                "jdbc:h2:mem:cc-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("claimcenter-schema.sql")).execute(db);
        jdbc = new JdbcTemplate(db);
        outbox = new ClaimEventOutbox(jdbc, mapper);
        cc = new ClaimCenterController(new ClaimCenterStore(jdbc, mapper), outbox, 1);

        subscriber = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        subscriber.createContext("/events", exchange -> {
            received.add(Map.of(
                    "body", new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8),
                    "signature", String.valueOf(exchange.getRequestHeaders().getFirst(ClaimEventDispatcher.SIGNATURE_HEADER)),
                    "eventId", String.valueOf(exchange.getRequestHeaders().getFirst(ClaimEventDispatcher.EVENT_ID_HEADER))));
            exchange.sendResponseHeaders(reply.get(), -1);
            exchange.close();
        });
        subscriber.start();
    }

    @AfterEach
    void stop() {
        if (subscriber != null) {
            subscriber.stop(0);
        }
    }

    private ClaimEventDispatcher dispatcher() {
        return new ClaimEventDispatcher(outbox,
                "http://127.0.0.1:" + subscriber.getAddress().getPort() + "/events", SECRET);
    }

    private static FNOLPayload fnol() {
        return new FNOLPayload("POL-AUTO-112233", LocalDate.of(2026, 10, 20), "CA", "Rear-ended at a light",
                "Avery Johnson", "01/02/1980", null, List.of("mock://photo/normal.jpg"), "WEB", null, null,
                new BigDecimal("3000"), null);
    }

    @Test
    @DisplayName("Reporting a loss creates a draft holding the FNOL; submitting it once queues one claim.submitted event")
    void draftThenSubmitQueuesOneEvent() throws Exception {
        start();
        ClaimCreateResponse draft = cc.reportLoss(fnol());
        assertThat(draft.status()).isEqualTo(ClaimEventOutbox.DRAFT);
        assertThat(cc.get(draft.claimId()).status()).isEqualTo(ClaimEventOutbox.DRAFT);
        assertThat(cc.fnol(draft.claimId()).policyNumber()).isEqualTo("POL-AUTO-112233");
        assertThat(cc.events(draft.claimId(), false)).isEmpty();   // a draft announces nothing

        assertThat(cc.submit(draft.claimId()).status()).isEqualTo(ClaimEventOutbox.SUBMITTED);
        assertThatThrownBy(() -> cc.submit(draft.claimId()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        List<Map<String, Object>> events = cc.events(draft.claimId(), true);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).get("event_type")).isEqualTo(ClaimEventOutbox.CLAIM_SUBMITTED);
    }

    @Test
    @DisplayName("Events are signed and retried: a failing subscriber gets the event again after backoff; then it is delivered once")
    void signedDeliveryWithRetry() throws Exception {
        start();
        String id = cc.reportLoss(fnol()).claimId();
        cc.submit(id);
        ClaimEventDispatcher dispatcher = dispatcher();

        reply.set(500);
        dispatcher.deliver();
        assertThat(received).hasSize(1);
        Map<String, Object> failed = cc.events(id, true).get(0);
        assertThat(((Number) failed.get("attempts")).intValue()).isEqualTo(1);
        assertThat(failed.get("last_error")).isNotNull();

        dispatcher.deliver();   // backoff: not due yet
        assertThat(received).hasSize(1);

        jdbc.update("UPDATE claimcenter.events SET next_attempt_at = ?", Timestamp.from(Instant.now().minusSeconds(1)));
        reply.set(202);
        dispatcher.deliver();
        assertThat(received).hasSize(2);
        assertThat(cc.events(id, true)).isEmpty();

        dispatcher.deliver();   // delivered events are not sent again
        assertThat(received).hasSize(2);

        Map<String, String> delivery = received.get(1);
        assertThat(delivery.get("signature"))
                .isEqualTo("sha256=" + ClaimEventDispatcher.sign(SECRET.getBytes(StandardCharsets.UTF_8), delivery.get("body")));
        ClaimEvent event = mapper.readValue(delivery.get("body"), ClaimEvent.class);
        assertThat(event.type()).isEqualTo(ClaimEventOutbox.CLAIM_SUBMITTED);
        assertThat(event.subject()).isEqualTo(id);
        assertThat(event.id()).isEqualTo(delivery.get("eventId"));
        assertThat(event.data()).containsEntry("policyNumber", "POL-AUTO-112233");
        assertThat(delivery.get("body")).doesNotContain("Avery").doesNotContain("Rear-ended");   // no personal data in the event
    }

    @Test
    @DisplayName("The same loss reported again is refused (409, naming the original), and the original gets an activity")
    void duplicateLossIsRefused() throws Exception {
        start();
        String original = cc.reportLoss(fnol()).claimId();
        cc.reportLoss(fnol());   // an unsubmitted draft is not a reported claim yet: no duplicate
        cc.submit(original);

        assertThatThrownBy(() -> cc.reportLoss(fnol()))
                .isInstanceOfSatisfying(ClaimCenterController.DuplicateLossException.class,
                        e -> assertThat(e.originalClaimId).isEqualTo(original));
        assertThat(cc.duplicate(new ClaimCenterController.DuplicateLossException(original)).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(cc.activities(original)).extracting(CcActivity::subject)
                .containsExactly("Verify possible duplicate report");

        FNOLPayload otherDay = new FNOLPayload("POL-AUTO-112233", LocalDate.of(2026, 10, 21), "CA", "Another loss",
                null, null, null, List.of(), "WEB");
        assertThat(cc.reportLoss(otherDay).status()).isEqualTo(ClaimEventOutbox.DRAFT);
    }

    @Test
    @DisplayName("Without a signing secret nothing is sent")
    void noSecretNoDelivery() throws Exception {
        start();
        cc.submit(cc.reportLoss(fnol()).claimId());
        new ClaimEventDispatcher(outbox, "http://127.0.0.1:" + subscriber.getAddress().getPort() + "/events", "")
                .deliver();
        assertThat(received).isEmpty();
    }

    @Test
    @DisplayName("The AI extension writes routing, estimate and recommended reserve back onto the existing claim")
    void patchWritesBack() throws Exception {
        start();
        String id = cc.reportLoss(fnol()).claimId();
        cc.submit(id);
        Claim patched = cc.patch(id, new ClaimPatch("DRAFT_PENDING_REVIEW", new BigDecimal("4200"), new BigDecimal("4830")));
        assertThat(patched.status()).isEqualTo("DRAFT_PENDING_REVIEW");
        assertThat(patched.estimatedDamage()).isEqualByComparingTo("4200");
        assertThat(patched.reserve()).isEqualByComparingTo("4830");

        Claim statusOnly = cc.patch(id, new ClaimPatch("OPEN", null, null));
        assertThat(statusOnly.reserve()).isEqualByComparingTo("4830");   // unspecified fields are kept

        assertThatThrownBy(() -> cc.patch("CLM-2026-999999", new ClaimPatch("OPEN", null, null)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }
}
