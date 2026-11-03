package com.guidewire.fnol.api.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ClaimDraftRepository extends JpaRepository<ClaimDraftEntity, String> {
    List<ClaimDraftEntity> findByClaimIdOrderByCreatedAtDesc(String claimId);
}
