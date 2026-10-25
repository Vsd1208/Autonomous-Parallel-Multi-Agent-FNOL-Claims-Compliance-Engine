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

/** Mock ClaimCenter claims, review actions and attachments, stored in the "claimcenter" schema. */
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
                attachments(claimId), reviewActions(claimId)));
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
        return true;
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
