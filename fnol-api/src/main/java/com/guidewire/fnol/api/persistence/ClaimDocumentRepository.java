package com.guidewire.fnol.api.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ClaimDocumentRepository extends JpaRepository<ClaimDocumentEntity, String> {

    List<ClaimDocumentEntity> findByClaimIdOrderByUploadedAtAscNameAsc(String claimId);

    Optional<ClaimDocumentEntity> findByDocumentIdAndClaimId(String documentId, String claimId);

    /** Fingerprints of every uploaded photo, without loading the image bytes. */
    @Query("select d.claimId as claimId, d.sha256 as sha256, d.phash as phash from ClaimDocumentEntity d "
            + "where d.source = 'UPLOAD'")
    List<PhotoFingerprint> findUploadedPhotoFingerprints();

    interface PhotoFingerprint {
        String getClaimId();
        String getSha256();
        String getPhash();
    }
}
