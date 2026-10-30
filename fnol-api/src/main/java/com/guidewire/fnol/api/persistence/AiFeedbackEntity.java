package com.guidewire.fnol.api.persistence;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

/** One adjuster decision next to what the AI said about the same claim (table ai_feedback). */
@Entity
@Table(name = "ai_feedback")
public class AiFeedbackEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(length = 32, nullable = false)
    public String claimId;

    @Column(length = 16)
    public String lineOfBusiness;

    /** The damaged area (AUTO) or cause of loss (HOME) the AI named. */
    @Column(length = 40)
    public String aiDamageType;

    /** What the adjuster confirmed, when they did; null = not stated. */
    @Column(length = 40)
    public String adjusterDamageType;

    /** The AI's routing at intake, e.g. PENDING_ADVISORY_REVIEW (straight-through eligible) or HUMAN_REVIEW. */
    @Column(length = 40)
    public String aiStatus;

    @Column(length = 40, nullable = false)
    public String adjusterDecision;

    public BigDecimal aiReserve;
    public BigDecimal adjusterReserve;
    public BigDecimal aiConfidence;
    public BigDecimal rawConfidence;
    public BigDecimal probabilityMargin;
    public BigDecimal secondOpinionAgreement;

    @Column(length = 20)
    public String photoSubject;

    /** The model, prompt hash and thinking budget that produced the AI answer; null = mock or fallback. */
    @Column(length = 80)
    public String visionSource;

    @Column(length = 500)
    public String gateFlags;

    @Column(length = 64)
    public String adjusterId;

    @Column(nullable = false)
    public Instant decidedAt;
}
