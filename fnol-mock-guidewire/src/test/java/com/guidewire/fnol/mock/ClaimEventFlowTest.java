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

        assertThat(cc.submit(draft.claimId()).status()).isEqualTo(ClaimEventOutbox.OPEN);
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
    @DisplayName("A decision announces claim.decided with what the feedback loop needs, no free text, and moves the work queue; other updates announce nothing")
    void decisionAnnouncesAnEvent() throws Exception {
        start();
        String id = cc.reportLoss(fnol()).claimId();
        cc.submit(id);
        jdbc.update("UPDATE claimcenter.events SET delivered_at = next_attempt_at");   // claim.submitted is done with

        cc.update(id, new ClaimUpdateRequest("OPEN", "photos please", "PHOTOS_REQUESTED", "ADJ-001", null,
                "NEEDS_MORE_INFO"));
        cc.update(id, new ClaimUpdateRequest("OPEN", "got them"));   // no action at all
        assertThat(cc.events(id, true)).isEmpty();

        Claim decided = cc.update(id, new ClaimUpdateRequest("OPEN", "private adjuster note", "APPROVED_BY_ADJUSTER",
                "ADJ-001", new BigDecimal("3500"), "APPROVED_BY_ADJUSTER", "invoice lower", "REAR_BUMPER",
                new BigDecimal("4500"), "INVOICE"));
        assertThat(decided.workQueue()).isEqualTo("APPROVED_BY_ADJUSTER");
        assertThat(cc.events(id, true)).hasSize(1);
        String payload = outbox.due(5).get(0).payload();
        ClaimEvent event = mapper.readValue(payload, ClaimEvent.class);
        assertThat(event.type()).isEqualTo(ClaimEventOutbox.CLAIM_DECIDED);
        assertThat(event.subject()).isEqualTo(id);
        assertThat(event.data()).containsEntry("decision", "APPROVED_BY_ADJUSTER").containsEntry("repairCostSource", "INVOICE")
                .containsEntry("confirmedDamageType", "REAR_BUMPER");
        assertThat(Instant.parse((String) event.data().get("decidedAt")))
                .isEqualTo(decided.reviewActions().get(decided.reviewActions().size() - 1).at());
        assertThat(payload).doesNotContain("private adjuster note");
    }

    @Test
    @DisplayName("A finished claim refuses another status (409); repeating its status changes nothing and announces nothing")
    void finishedClaimRefusesChange() throws Exception {
        start();
        String id = cc.reportLoss(fnol()).claimId();
        cc.submit(id);
        cc.update(id, new ClaimUpdateRequest("CLOSED", "no cover", "REJECTED_BY_ADJUSTER", "ADJ-001", null));
        int events = cc.events(id, false).size();

        assertThatThrownBy(() -> cc.update(id, new ClaimUpdateRequest("OPEN", null, "APPROVED_BY_ADJUSTER", "ADJ-002", null)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(cc.update(id, new ClaimUpdateRequest("CLOSED", "again", "REJECTED_BY_ADJUSTER", "ADJ-002", null)).reviewActions())
                .hasSize(1);
        assertThat(cc.events(id, false)).hasSize(events);
    }

    @Test
    @DisplayName("Work queues list claims oldest first with paging: unanalysed ones too; drafts are in no queue")
    void workQueues() throws Exception {
        start();
        String draft = cc.reportLoss(fnol()).claimId();
        FNOLPayload other = new FNOLPayload("POL-AUTO-112233", LocalDate.of(2026, 10, 21), "CA", "Another", null, null,
                null, List.of(), "WEB");
        String submitted = cc.reportLoss(other).claimId();
        cc.submit(submitted);
        assertThat(cc.queue("ANALYSIS_PENDING", null, 0, 20).items()).extracting(ClaimQueueEntry::claimId).containsExactly(submitted);
        assertThat(cc.queue("ANALYSIS_PENDING,HUMAN_REVIEW", null, 0, 20).items()).extracting(ClaimQueueEntry::claimId)
                .doesNotContain(draft);

        cc.patch(submitted, new ClaimPatch("OPEN", null, new BigDecimal("800"), "PENDING_ADVISORY_REVIEW"));
        assertThat(cc.queue("ANALYSIS_PENDING", null, 0, 20).total()).isZero();
        ClaimQueueEntry entry = cc.queue("PENDING_ADVISORY_REVIEW", null, 0, 20).items().get(0);
        assertThat(entry.reserve()).isEqualByComparingTo("800");

        String created = cc.create(new ClaimCreateRequest("POL-AUTO-112233", LocalDate.of(2026, 10, 22), "CA",
                new BigDecimal("100"), new BigDecimal("115"), "OPEN", "PENDING_ADVISORY_REVIEW")).claimId();
        ClaimQueuePage first = cc.queue("PENDING_ADVISORY_REVIEW", null, 0, 1);
        assertThat(first.total()).isEqualTo(2);
        assertThat(first.items()).extracting(ClaimQueueEntry::claimId).containsExactly(submitted);
        assertThat(cc.queue("PENDING_ADVISORY_REVIEW", null, 1, 1).items()).extracting(ClaimQueueEntry::claimId).containsExactly(created);
        assertThatThrownBy(() -> cc.queue(" ", null, 0, 20)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    @DisplayName("A draft has a draft number; submitting opens the claim under its open number, under the same id")
    void draftNumberThenOpenNumber() throws Exception {
        start();
        ClaimCreateResponse draft = cc.reportLoss(fnol());
        assertThat(draft.claimNumber()).startsWith("DRAFT-").isNotEqualTo(draft.claimId());
        assertThat(cc.get(draft.claimId()).claimNumber()).isEqualTo(draft.claimNumber());
        assertThat(cc.get(draft.claimId()).workQueue()).isNull();   // a draft is on nobody's desk

        ClaimCreateResponse open = cc.submit(draft.claimId());
        assertThat(open.status()).isEqualTo("OPEN");
        assertThat(open.claimId()).isEqualTo(draft.claimId());
        assertThat(open.claimNumber()).isEqualTo(draft.claimId());
        Claim claim = cc.get(draft.claimId());
        assertThat(claim.status()).isEqualTo("OPEN");
        assertThat(claim.claimNumber()).isEqualTo(draft.claimId());
        assertThat(claim.workQueue()).isEqualTo(ClaimEventOutbox.ANALYSIS_PENDING);
    }

    @Test
    @DisplayName("An exposure needs a coverage, incident and claimant; it can exist with no reserve, and a person's decision sets it")
    void exposureRequirementsAndReserve() throws Exception {
        start();
        String id = cc.reportLoss(fnol()).claimId();
        cc.submit(id);
        for (CcExposure incomplete : List.of(
                new CcExposure(null, "VehicleDamage", "COLLISION", null, null, null, null, "Avery Johnson"),
                new CcExposure(null, "VehicleDamage", "COLLISION", null, null, null, "VehicleIncident", null),
                new CcExposure(null, "VehicleDamage", null, null, null, null, "VehicleIncident", "Avery Johnson"))) {
            assertThatThrownBy(() -> cc.addExposure(id, incomplete))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        }
        CcExposure exposure = cc.addExposure(id, new CcExposure(null, "VehicleDamage", "COLLISION", null, null, null,
                "VehicleIncident", "Avery Johnson"));
        assertThat(exposure.reserve()).isNull();

        cc.update(id, new ClaimUpdateRequest("OPEN", "photos please", "NEEDS_MORE_INFO", "ADJ-001", null));
        assertThat(cc.get(id).exposures().get(0).reserve()).as("an interim step posts nothing").isNull();

        Claim approved = cc.update(id, new ClaimUpdateRequest("OPEN", null, "APPROVED_BY_ADJUSTER", "ADJ-001", new BigDecimal("3200")));
        assertThat(approved.exposures().get(0).reserve()).isEqualByComparingTo("3200");
        assertThat(approved.reserve()).isEqualByComparingTo("3200");
        assertThat(approved.exposures().get(0).claimant()).isEqualTo("Avery Johnson");
    }

    @Test
    @DisplayName("A denial closes the claim and zeroes its exposure")
    void denialClosesTheClaim() throws Exception {
        start();
        String id = cc.reportLoss(fnol()).claimId();
        cc.submit(id);
        cc.addExposure(id, new CcExposure(null, "VehicleDamage", "COLLISION", null, null, null, "VehicleIncident", "Avery Johnson"));
        Claim denied = cc.update(id, new ClaimUpdateRequest("CLOSED", "no cover", "REJECTED_BY_ADJUSTER", "ADJ-001", null));
        assertThat(denied.status()).isEqualTo("CLOSED");
        assertThat(denied.workQueue()).isEqualTo("REJECTED_BY_ADJUSTER");
        assertThat(denied.exposures().get(0).reserve()).isEqualByComparingTo("0");
        assertThat(denied.exposures().get(0).status()).isEqualTo("closed");
    }

    @Test
    @DisplayName("Segment and assignee are written onto the claim, and a queue can be filtered by segment")
    void segmentAndAssignee() throws Exception {
        start();
        String id = cc.reportLoss(fnol()).claimId();
        cc.submit(id);
        Claim patched = cc.patch(id, new ClaimPatch(null, null, null, "HUMAN_REVIEW", "AUTO_STANDARD", "ADJ-001", "Auto claims queue"));
        assertThat(patched.segment()).isEqualTo("AUTO_STANDARD");
        assertThat(patched.assignedTo()).isEqualTo("ADJ-001");
        assertThat(patched.assignmentGroup()).isEqualTo("Auto claims queue");
        assertThat(cc.queue("HUMAN_REVIEW", "AUTO_STANDARD", 0, 20).items()).extracting(ClaimQueueEntry::assignedTo).containsExactly("ADJ-001");
        assertThat(cc.queue("HUMAN_REVIEW", "AUTO_COMPLEX", 0, 20).total()).isZero();
        assertThat(cc.queue("HUMAN_REVIEW", null, 0, 20).total()).isEqualTo(1);
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
        Claim patched = cc.patch(id, new ClaimPatch("OPEN", new BigDecimal("4200"), new BigDecimal("4830")));
        assertThat(patched.status()).isEqualTo("OPEN");
        assertThat(patched.estimatedDamage()).isEqualByComparingTo("4200");
        assertThat(patched.reserve()).isEqualByComparingTo("4830");

        Claim statusOnly = cc.patch(id, new ClaimPatch("OPEN", null, null));
        assertThat(statusOnly.reserve()).isEqualByComparingTo("4830");   // unspecified fields are kept

        assertThatThrownBy(() -> cc.patch("CLM-2026-999999", new ClaimPatch("OPEN", null, null)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }
}
