package com.guidewire.fnol.api.persistence;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** One accepted claim event and how far its processing has got. */
@Entity
@Table(name = "fnol_event_inbox")
public class EventInboxEntity {
    public static final String PENDING = "PENDING";
    public static final String PROCESSING = "PROCESSING";
    public static final String COMPLETED = "COMPLETED";
    public static final String RETRY_PENDING = "RETRY_PENDING";
    public static final String DEAD_LETTER = "DEAD_LETTER";

    @Id
    public String id;
    @Column(name = "event_id", nullable = false, unique = true)
    public String eventId;
    @Column(name = "event_type", nullable = false)
    public String eventType;
    @Column(name = "aggregate_id", nullable = false)
    public String aggregateId;
    @Column(name = "event_time")
    public Instant eventTime;
    @JsonIgnore
    @Column(nullable = false, columnDefinition = "TEXT")
    public String payload;
    @Column(name = "payload_hash", nullable = false)
    public String payloadHash;
    @Column(nullable = false)
    public String status;
    @Column(name = "attempt_count", nullable = false)
    public int attemptCount;
    @Column(name = "next_attempt_at", nullable = false)
    public Instant nextAttemptAt;
    @Column(name = "lease_owner")
    public String leaseOwner;
    @Column(name = "lease_expires_at")
    public Instant leaseExpiresAt;
    @Column(name = "last_error")
    public String lastError;
    @JsonIgnore
    @Column(name = "branch_a_json", columnDefinition = "TEXT")
    public String branchAJson;
    @JsonIgnore
    @Column(name = "branch_b_json", columnDefinition = "TEXT")
    public String branchBJson;
    @Column(name = "received_at", nullable = false)
    public Instant receivedAt;
    @Column(name = "completed_at")
    public Instant completedAt;
    @Column(name = "created_at", nullable = false)
    public Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;
}
