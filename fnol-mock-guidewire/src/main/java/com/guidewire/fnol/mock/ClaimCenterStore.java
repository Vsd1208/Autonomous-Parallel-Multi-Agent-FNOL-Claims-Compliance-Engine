package com.guidewire.fnol.mock;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guidewire.fnol.common.Models.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Mock ClaimCenter claims, review actions, attachments (documents), exposures, activities and notes, stored in the
 * "claimcenter" schema.
 */
@Repository
class ClaimCenterStore {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    ClaimCenterStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** Highest claim number issued so far (the digits after the last '-'), or 0 when there are none. */
    int highestClaimNumber() {
        return jdbc.queryForList("SELECT claim_id FROM claimcenter.claims", String.class).stream()
                .mapToInt(ClaimCenterStore::number).max().orElse(0);
    }

    private static int number(String claimId) {
        try {
            return Integer.parseInt(claimId.substring(claimId.lastIndexOf('-') + 1));
        } catch (RuntimeException e) {
            return 0;
        }
    }

    void insert(String claimId, String status, ClaimCreateRequest r) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                INSERT INTO claimcenter.claims (claim_id, status, policy_number, incident_date, state,
                    estimated_damage, reserve, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                claimId, status, r.policyNumber(), r.incidentDate() == null ? null : Date.valueOf(r.incidentDate()),
                r.state(), r.estimatedDamage(), r.reserve(), now, now);
    }

    Optional<Claim> find(String claimId) {
        List<Claim> rows = jdbc.query("SELECT * FROM claimcenter.claims WHERE claim_id = ?", (rs, i) -> {
            Date incident = rs.getDate("incident_date");
            return new Claim(rs.getString("claim_id"), rs.getString("status"), rs.getString("policy_number"),
                    incident == null ? null : incident.toLocalDate(), rs.getString("state"),
                    rs.getBigDecimal("estimated_damage"), rs.getBigDecimal("reserve"), rs.getString("notes"),
                    List.of(), List.of());
        }, claimId);
        return rows.stream().findFirst().map(c -> new Claim(c.claimId(), c.status(), c.policyNumber(),
                c.incidentDate(), c.state(), c.estimatedDamage(), c.reserve(), c.notes(),
                attachments(claimId), reviewActions(claimId), exposures(claimId), activities(claimId), notes(claimId)));
    }

    /** Applies an update and records it as a review action; false when the claim does not exist. */
    boolean update(String claimId, ClaimUpdateRequest r) {
        Instant now = Instant.now();
        int changed = jdbc.update("""
                UPDATE claimcenter.claims SET status = COALESCE(?, status), reserve = COALESCE(?, reserve),
                    notes = ?, updated_at = ? WHERE claim_id = ?""",
                r.status(), r.adjustedReserve(), r.notes(), Timestamp.from(now), claimId);
        if (changed == 0) {
            return false;
        }
        String status = jdbc.queryForObject("SELECT status FROM claimcenter.claims WHERE claim_id = ?",
                String.class, claimId);
        jdbc.update("""
                INSERT INTO claimcenter.review_actions (claim_id, acted_at, action, status, adjuster_id, notes,
                    adjusted_reserve) VALUES (?, ?, ?, ?, ?, ?, ?)""",
                claimId, Timestamp.from(now), r.action(), status, r.adjusterId(), r.notes(), r.adjustedReserve());
        applyDecision(claimId, r);
        return true;
    }

    /** ClaimCenter statuses that end the review: the AI-review activities are then done. */
    private static final Set<String> FINAL_STATUSES = Set.of("OPEN", "DENIED", "CLOSED");

    /**
     * A final adjuster decision completes the open review activities and settles the exposure reserve: the
     * adjuster's final reserve when given, zero and closed on a denial, closed when the claim is closed.
     */
    private void applyDecision(String claimId, ClaimUpdateRequest r) {
        if (r.status() == null || !FINAL_STATUSES.contains(r.status()) || r.action() == null) {
            return;
        }
        jdbc.update("UPDATE claimcenter.activities SET status = 'complete', completed_by = ? WHERE claim_id = ? AND status = 'open'",
                r.adjusterId(), claimId);
        if ("DENIED".equals(r.status())) {
            jdbc.update("UPDATE claimcenter.exposures SET reserve = 0, status = 'closed' WHERE claim_id = ?", claimId);
        } else if ("CLOSED".equals(r.status())) {
            jdbc.update("UPDATE claimcenter.exposures SET status = 'closed' WHERE claim_id = ?", claimId);
        } else if (r.adjustedReserve() != null) {
            jdbc.update("UPDATE claimcenter.exposures SET reserve = ? WHERE claim_id = ?", r.adjustedReserve(), claimId);
        }
    }

    CcExposure addExposure(String claimId, CcExposure e) {
        String id = "EXP-" + UUID.randomUUID().toString().substring(0, 8);
        String costType = e.costType() == null ? "claimcost" : e.costType();
        jdbc.update("""
                INSERT INTO claimcenter.exposures (exposure_id, claim_id, exposure_type, coverage_type, reserve, cost_type,
                    status, created_at) VALUES (?, ?, ?, ?, ?, ?, 'open', ?)""",
                id, claimId, e.exposureType(), e.coverageType(), e.reserve(), costType, Timestamp.from(Instant.now()));
        return new CcExposure(id, e.exposureType(), e.coverageType(), e.reserve(), costType, "open");
    }

    CcActivity addActivity(String claimId, CcActivity a) {
        String id = "ACT-" + UUID.randomUUID().toString().substring(0, 8);
        String priority = a.priority() == null ? "normal" : a.priority();
        jdbc.update("""
                INSERT INTO claimcenter.activities (activity_id, claim_id, subject, description, priority, due_date,
                    assigned_to, status, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, 'open', ?)""",
                id, claimId, a.subject(), a.description(), priority, a.dueDate() == null ? null : Date.valueOf(a.dueDate()),
                a.assignedTo(), Timestamp.from(Instant.now()));
        return new CcActivity(id, a.subject(), a.description(), priority, a.dueDate(), a.assignedTo(), "open", null);
    }

    CcNote addNote(String claimId, CcNote n) {
        String id = "NOTE-" + UUID.randomUUID().toString().substring(0, 8);
        String topic = n.topic() == null ? "general" : n.topic();
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO claimcenter.notes (note_id, claim_id, topic, subject, body, created_at)
                VALUES (?, ?, ?, ?, ?, ?)""",
                id, claimId, topic, n.subject(), n.body(), Timestamp.from(now));
        return new CcNote(id, topic, n.subject(), n.body(), now);
    }

    List<CcExposure> exposures(String claimId) {
        return jdbc.query("SELECT * FROM claimcenter.exposures WHERE claim_id = ? ORDER BY created_at, exposure_id",
                (rs, i) -> new CcExposure(rs.getString("exposure_id"), rs.getString("exposure_type"),
                        rs.getString("coverage_type"), rs.getBigDecimal("reserve"), rs.getString("cost_type"),
                        rs.getString("status")), claimId);
    }

    List<CcActivity> activities(String claimId) {
        return jdbc.query("SELECT * FROM claimcenter.activities WHERE claim_id = ? ORDER BY created_at, activity_id",
                (rs, i) -> {
                    Date due = rs.getDate("due_date");
                    return new CcActivity(rs.getString("activity_id"), rs.getString("subject"),
                            rs.getString("description"), rs.getString("priority"),
                            due == null ? null : due.toLocalDate(), rs.getString("assigned_to"),
                            rs.getString("status"), rs.getString("completed_by"));
                }, claimId);
    }

    List<CcNote> notes(String claimId) {
        return jdbc.query("SELECT * FROM claimcenter.notes WHERE claim_id = ? ORDER BY created_at, note_id",
                (rs, i) -> new CcNote(rs.getString("note_id"), rs.getString("topic"), rs.getString("subject"),
                        rs.getString("body"), rs.getTimestamp("created_at").toInstant()), claimId);
    }

    List<ClaimAttachment> documents(String claimId) {
        return attachments(claimId);
    }

    boolean exists(String claimId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM claimcenter.claims WHERE claim_id = ?",
                Integer.class, claimId);
        return n != null && n > 0;
    }

    void attach(String claimId, ClaimAttachment a) {
        jdbc.update("""
                INSERT INTO claimcenter.attachments (claim_id, name, content_type, body, attached_at)
                VALUES (?, ?, ?, ?, ?)""",
                claimId, a.name(), a.contentType(), toJson(a.body()), Timestamp.from(Instant.now()));
    }

    private List<ClaimAttachment> attachments(String claimId) {
        return jdbc.query("SELECT name, content_type, body FROM claimcenter.attachments WHERE claim_id = ? ORDER BY id",
                (rs, i) -> new ClaimAttachment(rs.getString("name"), rs.getString("content_type"),
                        fromJson(rs.getString("body"))), claimId);
    }

    private List<ClaimReviewAction> reviewActions(String claimId) {
        return jdbc.query("SELECT * FROM claimcenter.review_actions WHERE claim_id = ? ORDER BY id",
                (rs, i) -> new ClaimReviewAction(rs.getTimestamp("acted_at").toInstant(), rs.getString("action"),
                        rs.getString("status"), rs.getString("adjuster_id"), rs.getString("notes"),
                        rs.getBigDecimal("adjusted_reserve")), claimId);
    }

    private String toJson(Object body) {
        try {
            return body == null ? null : mapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Attachment body is not valid JSON", e);
        }
    }

    private Object fromJson(String json) {
        try {
            return json == null ? null : mapper.readValue(json, Object.class);
        } catch (JsonProcessingException e) {
            return json;
        }
    }
}
