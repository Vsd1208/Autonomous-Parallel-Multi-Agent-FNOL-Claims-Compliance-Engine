package com.guidewire.fnol.api.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guidewire.fnol.common.Models.*;
import jakarta.persistence.*;
import java.math.*;
import java.time.*;

/** A claim row. Never holds the description, SSN, DOB, claimant name, phone or email. */
@Entity
@Table(name = "claims")
public class ClaimEntity {
  @Id
  @Column(name = "claim_id")
  public String claimId;
  public String policyNumber;
  public String status;
  public LocalDate incidentDate;
  public String state;
  public BigDecimal estimatedDamage;
  /** Recommended payment: min(claimed, AI estimate) − deductible, capped at the coverage limit. */
  public BigDecimal reserve;
  /** What the claimant asked for (null when not stated). */
  public BigDecimal claimedAmount;
  /** Policy deductible applied to the reserve. */
  public BigDecimal deductible;
  public String adjusterDecision;
  public String adjusterId;
  @Column(length = 2000)
  public String adjusterNotes;
  public Instant reviewedAt;
  public Instant createdAt;
  public Instant updatedAt;

  /**
   * The intake's full analysis (FNOLResponse) for adjusters on any browser, minus the redacted description
   * text and the audit records. Not part of the /status JSON; served by GET /fnol/{id}/analysis.
   */
  @com.fasterxml.jackson.annotation.JsonIgnore
  @Column(columnDefinition = "TEXT", name = "analysis_json")
  public String analysisJson;

  /** Serialised ExplanationObject JSON — the "WHY" panel, persisted for adjuster retrieval. */
  @Column(columnDefinition = "TEXT", name = "explanation_json")
  public String explanationJson;

  /** Intake channel: WEB | MOBILE | PHONE | API */
  @Column(length = 32)
  public String channel;

  /** Why a failed intake was saved for human attention (REJ-... claims only). Never contains PII. */
  @Column(length = 500)
  public String failureReason;

  /** LOW | MEDIUM | HIGH from the WHY panel. */
  @Column(length = 16)
  public String severityGate;

  /** Comma-separated Human Review Point 1 flags. */
  @Column(length = 500)
  public String inputFlags;

  /** SYNCED | FAILED | NOT_FILED — whether ClaimCenter has the latest advisory and decision. */
  @Column(length = 16)
  public String ccSyncStatus;

  /** Comma-separated reconciliation-gate consistency flags, plus DUPLICATE_SUBMISSION. */
  @Column(length = 500)
  public String gateFlags;

  /** Reserve set by the adjuster when overriding the AI recommendation (reserve keeps the AI value). */
  public BigDecimal adjusterReserve;

  /** Why the adjuster changed the AI finding. */
  @Column(length = 500)
  public String overrideReason;

  private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

  public static ClaimEntity from(FNOLResponse r, FNOLPayload p) {
    var e = new ClaimEntity();
    e.claimId        = r.claimId();
    e.policyNumber   = p.policyNumber();
    e.status         = r.status();
    e.incidentDate   = p.incidentDate();
    e.state          = p.state();
    e.estimatedDamage = r.branchA().damageAssessment().estimatedDamage();
    e.reserve        = r.branchA().reserve().recommendedReserve();
    e.claimedAmount  = p.claimedAmount();
    e.deductible     = r.branchA().reserve().deductible();
    e.channel        = p.channel();
    e.severityGate   = r.severityGate();
    e.inputFlags     = r.inputFlags() == null || r.inputFlags().isEmpty() ? null : String.join(",", r.inputFlags());
    e.ccSyncStatus   = r.ccSyncStatus();
    e.gateFlags      = r.gateFlags() == null || r.gateFlags().isEmpty() ? null : String.join(",", r.gateFlags());
    e.createdAt      = Instant.now();
    e.updatedAt      = e.createdAt;
    try {
      e.explanationJson = MAPPER.writeValueAsString(r.explanation());
    } catch (Exception ex) {
      e.explanationJson = "{\"error\":\"serialisation failed\"}";
    }
    try {
      e.analysisJson = MAPPER.writeValueAsString(withoutPersonalText(r));
    } catch (Exception ex) {
      e.analysisJson = null;   // the claim is still saved; the console falls back to the saved row
    }
    return e;
  }

  /** The analysis as stored: no description text (even redacted) and no audit records. */
  static FNOLResponse withoutPersonalText(FNOLResponse r) {
    BranchBResult b = r.branchB();
    BranchBResult safeB = b == null ? null : new BranchBResult(
        b.pii() == null ? null : new PIIResult(null, b.pii().detectedTypes(), b.pii().piiDetected(),
            b.pii().redactionConfidence()),
        b.deadline(), b.subrogation(), java.util.List.of());
    return new FNOLResponse(r.claimId(), r.status(), r.branchA(), safeB, r.policyContext(), r.explanation(),
        r.severityGate(), r.eligibleForAdvisoryPrefill(), r.requiresHumanValidation(), r.notes(), r.inputFlags(),
        r.ccSyncStatus(), r.gateFlags());
  }
}
