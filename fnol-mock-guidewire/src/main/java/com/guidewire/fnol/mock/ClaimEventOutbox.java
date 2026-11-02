package com.guidewire.fnol.mock;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guidewire.fnol.common.Models.ClaimEvent;
import com.guidewire.fnol.common.Models.ClaimPatch;
import com.guidewire.fnol.common.Models.ClaimUpdateRequest;
import com.guidewire.fnol.common.Models.FNOLPayload;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The claims system's FNOL reports and its event outbox. A change and the event that reports it are written in one
 * transaction (transactional outbox), so an event is never lost and never sent for a change that did not happen;
 * {@link ClaimEventDispatcher} delivers pending events with retries.
 */
@Repository
class ClaimEventOutbox {

    static final String CLAIM_SUBMITTED = "claim.submitted";
    static final String CLAIM_DECIDED = "claim.decided";
    static final String SOURCE = "claims-system";
    /** A claim is a draft while being reported and open once submitted; a person's decision can close it. */
    static final String DRAFT = "DRAFT";
    static final String OPEN = "OPEN";
    /** The work queue of an open claim until its AI analysis is on the record. */
    static final String ANALYSIS_PENDING = "ANALYSIS_PENDING";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    ClaimEventOutbox(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** A new draft claim holding the FNOL as reported. */
    @Transactional
    void createDraft(String claimId, FNOLPayload fnol) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                INSERT INTO claimcenter.claims (claim_id, claim_number, status, policy_number, incident_date, state, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                claimId, draftNumber(claimId), DRAFT, fnol.policyNumber(), fnol.incidentDate() == null ? null : Date.valueOf(fnol.incidentDate()),
                fnol.state(), now, now);
        jdbc.update("INSERT INTO claimcenter.fnol_reports (claim_id, payload, created_at) VALUES (?, ?, ?)",
                claimId, json(fnol), now);
    }

    /**
     * A claim already reported for the same loss (policy, loss date, state), drafts excepted: a draft is not reported
     * yet. Used for the duplicate check when a new loss is reported.
     */
    Optional<String> reportedClaimFor(FNOLPayload fnol) {
        return jdbc.queryForList("""
                SELECT claim_id FROM claimcenter.claims
                WHERE policy_number = ? AND incident_date = ? AND state = ? AND status <> ? ORDER BY created_at""",
                String.class, fnol.policyNumber(), Date.valueOf(fnol.incidentDate()), fnol.state(), DRAFT)
                .stream().findFirst();
    }

    /** A draft has a draft number; submitting gives it its open claim number. The id stays the same throughout. */
    static String draftNumber(String claimId) {
        return "DRAFT-" + claimId.substring(claimId.lastIndexOf('-') + 1);
    }

    Optional<FNOLPayload> fnol(String claimId) {
        return jdbc.query("SELECT payload FROM claimcenter.fnol_reports WHERE claim_id = ?",
                (rs, i) -> read(rs.getString("payload"), FNOLPayload.class), claimId).stream().findFirst();
    }

    /**
     * Submits a draft: it becomes an open claim with its open number and a claim.submitted event, together. A claim
     * cannot go back to draft afterwards, only be closed. False when the claim is not a draft
     * (already submitted, or created another way).
     */
    @Transactional
    boolean submit(String claimId) {
        int changed = jdbc.update("""
                UPDATE claimcenter.claims SET status = ?, claim_number = ?, work_queue = ?, updated_at = ?
                WHERE claim_id = ? AND status = ?""",
                OPEN, claimId, ANALYSIS_PENDING, Timestamp.from(Instant.now()), claimId, DRAFT);
        if (changed == 0) {
            return false;
        }
        FNOLPayload fnol = fnol(claimId).orElseThrow();
        enqueue(CLAIM_SUBMITTED, claimId, Map.of("claimId", claimId, "policyNumber", fnol.policyNumber(),
                "channel", fnol.channel() == null ? "UNKNOWN" : fnol.channel()));
        return true;
    }

    /** A partial update by an integration (e.g. the AI extension's routing, estimate and recommended reserve). */
    boolean patch(String claimId, ClaimPatch p) {
        return jdbc.update("""
                UPDATE claimcenter.claims SET status = COALESCE(?, status), estimated_damage = COALESCE(?, estimated_damage),
                    reserve = COALESCE(?, reserve), work_queue = COALESCE(?, work_queue), updated_at = ? WHERE claim_id = ?""",
                p.status(), p.estimatedDamage(), p.reserve(), p.workQueue(), Timestamp.from(Instant.now()), claimId) > 0;
    }

    /**
     * Announces an adjuster decision. It carries what the AI's feedback loop needs, but not the free-text notes:
     * a subscriber that wants them reads them from the claim.
     */
    void decided(String claimId, ClaimUpdateRequest r, Instant decidedAt) {
        Map<String, Object> data = new HashMap<>();
        data.put("claimId", claimId);
        data.put("decision", r.action());
        data.put("adjusterId", r.adjusterId());
        data.put("decidedAt", decidedAt.toString());
        data.put("adjustedReserve", r.adjustedReserve());
        data.put("overrideReason", r.overrideReason());
        data.put("confirmedDamageType", r.confirmedDamageType());
        data.put("finalRepairCost", r.finalRepairCost());
        data.put("repairCostSource", r.repairCostSource());
        enqueue(CLAIM_DECIDED, claimId, data);
    }

    void enqueue(String type, String claimId, Map<String, Object> data) {
        Instant now = Instant.now();
        ClaimEvent event = new ClaimEvent(UUID.randomUUID().toString(), type, SOURCE, now, claimId, data);
        jdbc.update("""
                INSERT INTO claimcenter.events (event_id, event_type, claim_id, payload, created_at, next_attempt_at)
                VALUES (?, ?, ?, ?, ?, ?)""",
                event.id(), type, claimId, json(event), Timestamp.from(now), Timestamp.from(now));
    }

    record Pending(String eventId, String payload, int attempts) {}

    List<Pending> due(int limit) {
        return jdbc.query("""
                SELECT event_id, payload, attempts FROM claimcenter.events
                WHERE delivered_at IS NULL AND next_attempt_at <= ? ORDER BY created_at LIMIT ?""",
                (rs, i) -> new Pending(rs.getString("event_id"), rs.getString("payload"), rs.getInt("attempts")),
                Timestamp.from(Instant.now()), limit);
    }

    void delivered(String eventId) {
        jdbc.update("UPDATE claimcenter.events SET delivered_at = ?, attempts = attempts + 1, last_error = NULL WHERE event_id = ?",
                Timestamp.from(Instant.now()), eventId);
    }

    /** Exponential backoff: 2, 4, 8 ... seconds, at most 5 minutes between attempts. */
    void failed(String eventId, int attempts, String error) {
        long wait = Math.min(300, 1L << Math.min(attempts + 1, 9));
        jdbc.update("UPDATE claimcenter.events SET attempts = attempts + 1, next_attempt_at = ?, last_error = ? WHERE event_id = ?",
                Timestamp.from(Instant.now().plus(Duration.ofSeconds(wait))),
                error == null ? null : error.substring(0, Math.min(500, error.length())), eventId);
    }

    /** Events for operations: all of them for a claim, or the undelivered ones. */
    List<Map<String, Object>> list(String claimId, boolean undeliveredOnly) {
        List<String> where = new java.util.ArrayList<>();
        if (claimId != null) where.add("claim_id = ?");
        if (undeliveredOnly) where.add("delivered_at IS NULL");
        String sql = "SELECT event_id, event_type, claim_id, created_at, attempts, delivered_at, last_error FROM claimcenter.events"
                + (where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where)) + " ORDER BY created_at";
        return claimId != null ? jdbc.queryForList(sql, claimId) : jdbc.queryForList(sql);
    }

    private String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot store as JSON", e);
        }
    }

    private <T> T read(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored FNOL is not readable", e);
        }
    }
}
