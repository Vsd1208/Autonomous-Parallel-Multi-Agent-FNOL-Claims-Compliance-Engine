package com.guidewire.fnol.api.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditRepository extends JpaRepository<AuditEntity, String> {

    /** All audit events for a claim in chain order (seq follows insertion, i.e. timestamp, order). */
    List<AuditEntity> findByClaimIdOrderBySeqAscTimestampAsc(String claimId);
}
