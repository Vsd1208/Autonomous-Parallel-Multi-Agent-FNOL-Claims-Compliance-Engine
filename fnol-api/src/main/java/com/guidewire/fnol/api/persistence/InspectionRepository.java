package com.guidewire.fnol.api.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface InspectionRepository extends JpaRepository<InspectionEntity, String> {

    List<InspectionEntity> findByClaimIdOrderByAssignedAtAsc(String claimId);

    boolean existsByClaimIdAndInspectorId(String claimId, String inspectorId);

    List<InspectionEntity> findByInspectorIdOrderByAssignedAtDesc(String inspectorId);

    List<InspectionEntity> findByClaimIdAndInspectorIdOrderByAssignedAtAsc(String claimId, String inspectorId);

    Optional<InspectionEntity> findByInspectionIdAndClaimId(String inspectionId, String claimId);

    boolean existsByClaimIdAndStatus(String claimId, String status);
}
