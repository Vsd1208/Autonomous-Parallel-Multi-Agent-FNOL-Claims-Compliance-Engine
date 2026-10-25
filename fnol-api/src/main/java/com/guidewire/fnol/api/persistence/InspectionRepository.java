package com.guidewire.fnol.api.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface InspectionRepository extends JpaRepository<InspectionEntity, String> {

    List<InspectionEntity> findByClaimIdOrderByAssignedAtAsc(String claimId);

    Optional<InspectionEntity> findByInspectionIdAndClaimId(String inspectionId, String claimId);

    boolean existsByClaimIdAndStatus(String claimId, String status);
}
