package com.guidewire.fnol.api.persistence;

import com.guidewire.fnol.common.Models.AuditRecord;
import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "audit_trail")
public class AuditEntity {
    @Id
    public String eventId;
    public String claimId;
    public String agentName;
    public Instant timestamp;
    public String inputHash;
    public String outputHash;
    public String status;

    /** SHA-256(previous chainHash + inputHash + outputHash); see {@code AuditService}. */
    @Column(length = 64)
    public String chainHash;

    /** 1-based position in the claim's chain. */
    public Integer seq;

    /** SYSTEM, or the adjuster ID for human events. */
    @Column(length = 128)
    public String actor;

    public static AuditEntity from(AuditRecord a) {
        var e = new AuditEntity();
        e.eventId = a.eventId();
        e.claimId = a.claimId();
        e.agentName = a.agentName();
        e.timestamp = a.timestamp();
        e.inputHash = a.inputHash();
        e.outputHash = a.outputHash();
        e.status = a.status();
        return e;
    }
}
