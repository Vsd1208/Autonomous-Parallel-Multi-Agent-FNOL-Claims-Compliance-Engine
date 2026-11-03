package com.guidewire.fnol.api.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReserveApprovalRepository extends JpaRepository<ReserveApprovalEntity, String> {
    List<ReserveApprovalEntity> findByClaimIdOrderByRequestedAtDesc(String claimId);

    long countByAssignedToAndStatus(String assignedTo, String status);
}
