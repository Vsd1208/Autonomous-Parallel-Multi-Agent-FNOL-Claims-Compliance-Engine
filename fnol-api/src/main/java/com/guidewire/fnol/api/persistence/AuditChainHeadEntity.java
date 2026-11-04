package com.guidewire.fnol.api.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Where a claim's audit chain ends. Its row is what an append locks. */
@Entity
@Table(name = "audit_chain_heads")
public class AuditChainHeadEntity {
    @Id
    @Column(name = "claim_id")
    public String claimId;
    @Column(name = "last_seq", nullable = false)
    public int lastSeq;
    @Column(name = "head_hash", nullable = false)
    public String headHash;
}
