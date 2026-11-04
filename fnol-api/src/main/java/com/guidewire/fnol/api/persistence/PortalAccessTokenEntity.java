package com.guidewire.fnol.api.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** A claim-access token, by its hash. */
@Entity
@Table(name = "portal_access_tokens")
public class PortalAccessTokenEntity {
    @Id
    @Column(name = "token_hash", length = 64)
    public String tokenHash;
    @Column(name = "claim_id", nullable = false)
    public String claimId;
    @Column(name = "expires_at", nullable = false)
    public Instant expiresAt;
    @Column(name = "revoked_at")
    public Instant revokedAt;
    @Column(name = "created_at", nullable = false)
    public Instant createdAt;
}
