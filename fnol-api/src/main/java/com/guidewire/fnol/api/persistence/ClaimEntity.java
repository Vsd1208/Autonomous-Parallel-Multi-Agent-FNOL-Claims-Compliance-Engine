package com.guidewire.fnol.api.persistence;
import com.guidewire.fnol.common.Models.*;import jakarta.persistence.*;import java.math.*;import java.time.*;
@Entity @Table(name="claims") public class ClaimEntity {
  @Id @Column(name="claim_id") public String claimId; public String policyNumber; public String status; public LocalDate incidentDate; public String state; public BigDecimal estimatedDamage; public BigDecimal reserve; public Instant createdAt; public Instant updatedAt;
  public static ClaimEntity from(FNOLResponse r,FNOLPayload p){ var e=new ClaimEntity(); e.claimId=r.claimId(); e.policyNumber=p.policyNumber(); e.status=r.status(); e.incidentDate=p.incidentDate(); e.state=p.state(); e.estimatedDamage=r.branchA().damageAssessment().estimatedDamage(); e.reserve=r.branchA().reserve().recommendedReserve(); e.createdAt=Instant.now(); e.updatedAt=e.createdAt; return e; }
}
