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

    /** The real repair cost (before the deductible): an inspection's accident-related amount or the adjuster's figure. */
    public BigDecimal finalRepairCost;

    /**
     * What the final repair cost rests on: INVOICE, BODY_SHOP_ESTIMATE, INSPECTION, INVESTIGATION (evidence: the cost
     * model learns from these), ADJUSTER_JUDGEMENT (kept, never learned from) or INVESTIGATION_HOLD (a figure
     * entered on a claim sent to investigation, held until the investigator reports; never learned from); null when
     * no cost was known.
     */
    @Column(length = 20)
    public String costSource;

    /** What the AI estimated (Gemini, or the published range for an on-premise reading). */
    public BigDecimal aiEstimate;

    /** The on-premise model's visible-extent band (SMALL / MEDIUM / LARGE); null when not measured. */
    @Column(length = 10)
    public String extentBand;

    /** The repair-cost table's estimate for the area and band (published or learned), for comparison. */
    public BigDecimal publishedEstimate;

    @Column(length = 64)
    public String adjusterId;

    /** Seconds between opening the claim and deciding, as the workspace reported it. Signed-in people only. */
    @Column(name = "handling_seconds")
    public Integer handlingSeconds;

    /** How useful the adjuster found the AI advisory, 1-5. Optional; signed-in people only. */
    @Column(name = "advisory_rating")
    public Integer advisoryRating;

    @Column(nullable = false)
    public Instant decidedAt;
}
