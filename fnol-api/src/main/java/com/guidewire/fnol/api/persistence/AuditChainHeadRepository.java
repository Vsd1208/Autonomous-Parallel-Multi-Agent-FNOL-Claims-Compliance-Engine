package com.guidewire.fnol.api.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AuditChainHeadRepository extends JpaRepository<AuditChainHeadEntity, String> {

    /** Makes sure the row exists without failing the transaction if another instance got there first. */
    @Modifying
    @Query(value = "INSERT INTO audit_chain_heads (claim_id, last_seq, head_hash) VALUES (:claimId, 0, '') ON CONFLICT DO NOTHING", nativeQuery = true)
    void ensureExists(@Param("claimId") String claimId);

    /** Holds the claim's chain until this transaction ends. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from AuditChainHeadEntity h where h.claimId = :claimId")
    Optional<AuditChainHeadEntity> lockHead(@Param("claimId") String claimId);

    List<AuditChainHeadEntity> findAllByOrderByClaimIdAsc();
}
