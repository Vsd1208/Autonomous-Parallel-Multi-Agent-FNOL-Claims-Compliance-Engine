package com.guidewire.fnol.api.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ClaimDocumentRepository extends JpaRepository<ClaimDocumentEntity, String> {

    List<ClaimDocumentEntity> findByClaimIdOrderByUploadedAtAscNameAsc(String claimId);

    Optional<ClaimDocumentEntity> findByDocumentIdAndClaimId(String documentId, String claimId);
}
