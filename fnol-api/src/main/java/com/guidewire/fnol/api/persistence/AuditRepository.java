package com.guidewire.fnol.api.persistence;
import org.springframework.data.jpa.repository.*;
import java.util.List;

public interface AuditRepository extends JpaRepository<AuditEntity, String> {
    /** Fetch all audit trail events for a specific claim — used by PDF export. */
    List<AuditEntity> findByClaimId(String claimId);
}
