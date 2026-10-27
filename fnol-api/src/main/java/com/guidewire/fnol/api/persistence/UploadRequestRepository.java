package com.guidewire.fnol.api.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UploadRequestRepository extends JpaRepository<UploadRequestEntity, String> {

    Optional<UploadRequestEntity> findByTokenSha256(String tokenSha256);

    List<UploadRequestEntity> findByClaimIdOrderByCreatedAtAsc(String claimId);
}
