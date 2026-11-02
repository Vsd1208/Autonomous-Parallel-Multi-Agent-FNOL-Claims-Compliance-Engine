package com.guidewire.fnol.api.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/** A decision held because its reserve is above the person's authority, with what is needed to apply it once approved. */
@Entity
@Table(name = "reserve_approvals")
public class ReserveApprovalEntity {
    @Id
    @Column(name = "approval_id")
    public String approvalId;
    @Column(name = "claim_id", nullable = false)
    public String claimId;
    @Column(nullable = false)
    public String decision;
    @Column(nullable = false)
    public BigDecimal amount;
    @Column(name = "previous_status", nullable = false)
    public String previousStatus;
    @Column(name = "requested_by", nullable = false)
    public String requestedBy;
    @Column(name = "requested_role")
    public String requestedRole;
    @Column(name = "requested_at", nullable = false)
    public Instant requestedAt;
    @Column(name = "assigned_to")
    public String assignedTo;
    @Column(columnDefinition = "TEXT")
    public String notes;
    @Column(name = "adjusted_reserve")
    public BigDecimal adjustedReserve;
    @Column(name = "override_reason")
    public String overrideReason;
    @Column(name = "confirmed_damage_type")
    public String confirmedDamageType;
    @Column(name = "final_repair_cost")
    public BigDecimal finalRepairCost;
    @Column(name = "repair_cost_source")
    public String repairCostSource;
    @Column(name = "handling_seconds")
    public Integer handlingSeconds;
    @Column(name = "advisory_rating")
    public Integer advisoryRating;
    @Column(nullable = false)
    public String status;
    @Column(name = "decided_by")
    public String decidedBy;
    @Column(name = "decided_at")
    public Instant decidedAt;
    @Column(name = "decision_note", columnDefinition = "TEXT")
    public String decisionNote;
}
