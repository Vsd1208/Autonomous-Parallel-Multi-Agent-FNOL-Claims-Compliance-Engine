package com.guidewire.fnol.api.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guidewire.fnol.common.Models.*;
import jakarta.persistence.*;
import java.math.*;
import java.time.*;

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
  public BigDecimal reserve;
  public String adjusterDecision;
  public String adjusterId;
  @Column(length = 2000)
  public String adjusterNotes;
  public Instant reviewedAt;
  public Instant createdAt;
  public Instant updatedAt;

  /** Serialised ExplanationObject JSON — the "WHY" panel, persisted for adjuster retrieval. */
  @Column(columnDefinition = "TEXT", name = "explanation_json")
  public String explanationJson;

  /** Intake channel: WEB | MOBILE | PHONE | API */
  @Column(length = 32)
  public String channel;

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
    e.channel        = p.channel();
    e.createdAt      = Instant.now();
    e.updatedAt      = e.createdAt;
    try {
      e.explanationJson = MAPPER.writeValueAsString(r.explanation());
    } catch (Exception ex) {
      e.explanationJson = "{\"error\":\"serialisation failed\"}";
    }
    return e;
  }
}
