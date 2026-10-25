package com.guidewire.fnol.api.persistence;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;

import java.time.Instant;

/** An evidence document on a claim: an uploaded photo (with bytes) or a reference URI (without). */
@Entity
@Table(name = "claim_documents")
public class ClaimDocumentEntity {

    /** Largest stored image (matches the 5 MB evidence limit). */
    public static final int MAX_BYTES = 5 * 1024 * 1024;

    @Id
    @Column(length = 64)
    public String documentId;

    @Column(length = 32, nullable = false)
    public String claimId;

    @Column(length = 255, nullable = false)
    public String name;

    @Column(length = 64)
    public String contentType;

    public Integer sizeBytes;

    @Column(length = 64)
    public String sha256;

    /** Which view of the loss this photo shows: FRONT, REAR, LEFT, RIGHT, CLOSE_UP, ODOMETER_VIN, OVERVIEW or OTHER. */
    @Column(length = 16)
    public String angle;

    /** UPLOAD (bytes stored), URL (https reference) or MOCK (demo mock:// reference). */
    @Column(length = 16, nullable = false)
    public String source;

    @Column(length = 2000)
    public String sourceUri;

    /**
     * Image bytes; never serialised into JSON responses. Declared as bytea (matching V8) so Hibernate does
     * not choose a BLOB/OID type, which PostgreSQL stores out of row and H2's PostgreSQL mode rejects.
     */
    @JsonIgnore
    @Column(columnDefinition = "bytea")
    public byte[] content;

    @Column(nullable = false)
    public Instant uploadedAt;

    /** True when the image itself is stored and can be viewed. */
    public boolean isViewable() {
        return content != null;
    }
}
