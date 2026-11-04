package com.guidewire.fnol.api.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Failed attempts for one hashed key (client address, or claim number). */
@Entity
@Table(name = "portal_attempts")
public class PortalAttemptEntity {
    @Id
    @Column(name = "key_hash", length = 64)
    public String keyHash;
    @Column(nullable = false)
    public int failures;
    @Column(name = "window_start", nullable = false)
    public Instant windowStart;
    @Column(name = "blocked_until")
    public Instant blockedUntil;
}
