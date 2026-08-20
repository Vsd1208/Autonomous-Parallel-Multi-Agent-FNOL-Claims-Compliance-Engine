package com.guidewire.fnol.api.persistence;
import com.guidewire.fnol.common.Models.*;import jakarta.persistence.*;import java.time.*;
@Entity @Table(name="audit_trail") public class AuditEntity {
  @Id public String eventId; public String claimId; public String agentName; public Instant timestamp; public String inputHash; public String outputHash; public String status;
  public static AuditEntity from(AuditRecord a){ var e=new AuditEntity(); e.eventId=a.eventId(); e.claimId=a.claimId(); e.agentName=a.agentName(); e.timestamp=a.timestamp(); e.inputHash=a.inputHash(); e.outputHash=a.outputHash(); e.status=a.status(); return e; }
}
