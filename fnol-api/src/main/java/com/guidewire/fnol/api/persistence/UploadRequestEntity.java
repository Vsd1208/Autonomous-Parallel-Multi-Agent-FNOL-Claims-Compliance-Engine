package com.guidewire.fnol.api.persistence;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;

import java.time.Instant;

/** A one-time link for the claimant to add photos to an existing claim. The token itself is never stored. */
@Entity
@Table(name = "upload_requests")
public class UploadRequestEntity {

    @Id
    @Column(length = 64)
    public String requestId;

    @Column(length = 32, nullable = false)
    public String claimId;

    /** SHA-256 of the link token; the link cannot be rebuilt from it. */
    @JsonIgnore
    @Column(name = "token_sha256", length = 64, nullable = false, unique = true)
    public String tokenSha256;

    /** Comma-separated angles: FRONT, REAR, LEFT, RIGHT, CLOSE_UP, ODOMETER_VIN, OVERVIEW, OTHER. */
    @Column(length = 200, nullable = false)
    public String requestedAngles;

    @Column(length = 500)
    public String message;

    @Column(length = 64, nullable = false)
    public String requestedBy;

    /** OPEN, USED or CANCELLED (an expired OPEN link is reported as EXPIRED). */
    @Column(length = 16, nullable = false)
    public String status;

    @Column(nullable = false)
    public Instant createdAt;

    @Column(nullable = false)
    public Instant expiresAt;

    public Instant usedAt;

    public Integer photosReceived;

    /** Status as the claimant and adjuster see it: an OPEN link past its expiry is EXPIRED. */
    @Transient
    public String getEffectiveStatus() {
        return "OPEN".equals(status) && Instant.now().isAfter(expiresAt) ? "EXPIRED" : status;
    }
}
