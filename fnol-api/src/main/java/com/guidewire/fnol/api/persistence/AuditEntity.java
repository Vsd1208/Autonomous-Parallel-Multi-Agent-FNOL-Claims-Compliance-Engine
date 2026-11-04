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

    /** How chainHash was computed; null on rows from before version 2. */
    @Column(name = "hash_algorithm", length = 32)
    public String hashAlgorithm;

    @Column(name = "previous_hash", length = 64)
    public String previousHash;

    /** HUMAN_ACTOR, SERVICE_ACTOR or SYSTEM_ACTOR. */
    @Column(name = "actor_type", length = 20)
    public String actorType;

    @Column(name = "actor_role", length = 32)
    public String actorRole;

    @Column(name = "model_version", length = 160)
    public String modelVersion;

    @Column(name = "ruleset_version", length = 64)
    public String rulesetVersion;

    public static AuditEntity from(AuditRecord a) {
        var e = new AuditEntity();
        e.eventId = a.eventId();
        e.claimId = a.claimId();
        e.agentName = a.agentName();
        e.timestamp = a.timestamp();
        e.inputHash = a.inputHash();
        e.outputHash = a.outputHash();
        e.status = a.status();
        e.modelVersion = a.modelVersion();
        e.rulesetVersion = a.rulesetVersion();
        return e;
    }
}
