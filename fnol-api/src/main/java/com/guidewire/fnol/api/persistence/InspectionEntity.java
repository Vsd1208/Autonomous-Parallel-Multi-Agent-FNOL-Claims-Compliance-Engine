package com.guidewire.fnol.api.persistence;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** A damage inspection (appraisal) on a claim: assigned by an adjuster, reported by an inspector. */
@Entity
@Table(name = "inspections")
public class InspectionEntity {

    @Id
    @Column(length = 64)
    public String inspectionId;

    @Column(length = 32, nullable = false)
    public String claimId;

    @Column(length = 32, nullable = false)
    public String inspectorId;

    @Column(length = 100, nullable = false)
    public String inspectorName;

    /** FIELD (on-site), DESK (photos and documents) or SHOP (at the repair shop). */
    @Column(length = 16, nullable = false)
    public String inspectionType;

    @Column(length = 500, nullable = false)
    public String reason;

    /** ASSIGNED, COMPLETED or CANCELLED. */
    @Column(length = 16, nullable = false)
    public String status;

    @Column(length = 64, nullable = false)
    public String assignedBy;

    @Column(nullable = false)
    public Instant assignedAt;

    @Column(nullable = false)
    public LocalDate dueBy;

    public Instant completedAt;

    /** Repair cost of the damage this accident caused, as found by the inspector (earlier damage excluded). */
    public BigDecimal accidentRelatedAmount;

    public Boolean priorDamageFound;

    @Column(length = 2000)
    public String findings;

    /** APPROVE, REDUCE, DENY or INVESTIGATE. */
    @Column(length = 16)
    public String recommendation;

    /** min(claimed, accident-related amount) − deductible, never below 0. */
    public BigDecimal recommendedPayment;
}
