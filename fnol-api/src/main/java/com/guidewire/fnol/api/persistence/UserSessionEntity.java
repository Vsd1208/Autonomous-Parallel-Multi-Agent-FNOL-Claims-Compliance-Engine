package com.guidewire.fnol.api.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "user_sessions")
public class UserSessionEntity {
    @Id
    @Column(name = "token_hash")
    public String tokenHash;
    @Column(nullable = false)
    public String username;
    @Column(name = "created_at", nullable = false)
    public Instant createdAt;
    @Column(name = "expires_at", nullable = false)
    public Instant expiresAt;
}
