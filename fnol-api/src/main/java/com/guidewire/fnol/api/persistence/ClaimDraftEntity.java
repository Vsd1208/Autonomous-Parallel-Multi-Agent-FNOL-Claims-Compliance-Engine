package com.guidewire.fnol.api.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** A draft note or letter waiting for a person. */
@Entity
@Table(name = "claim_drafts")
public class ClaimDraftEntity {
    @Id
    @Column(name = "draft_id")
    public String draftId;
    @Column(name = "claim_id", nullable = false)
    public String claimId;
    @Column(nullable = false)
    public String kind;
    @Column(nullable = false)
    public String status;
    @Column(nullable = false, columnDefinition = "TEXT")
    public String body;
    @Column(name = "from_model", nullable = false)
    public boolean fromModel;
    @Column(name = "fallback_reason")
    public String fallbackReason;
    @Column(name = "tool_calls")
    public String toolCalls;
    @Column(name = "requested_by", nullable = false)
    public String requestedBy;
    @Column(name = "created_at", nullable = false)
    public Instant createdAt;
    @Column(name = "decided_by")
    public String decidedBy;
    @Column(name = "decided_at")
    public Instant decidedAt;
}
