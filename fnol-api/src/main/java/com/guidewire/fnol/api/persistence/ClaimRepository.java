package com.guidewire.fnol.api.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;

public interface ClaimRepository extends JpaRepository<ClaimEntity, String> {

    /**
     * DB-backed duplicate FNOL check — survives server restarts.
     * Ignores failed intakes saved for human attention (REJ-... rows in BLOCKED_COMPLIANCE or
     * HUMAN_REVIEW): they have no ClaimCenter claim, so a corrected resubmission is allowed.
     */
    @Query("""
            select count(c) > 0 from ClaimEntity c
            where c.policyNumber = :policyNumber and c.incidentDate = :incidentDate and c.state = :state
              and not (c.status in ('BLOCKED_COMPLIANCE', 'HUMAN_REVIEW') and c.claimId like 'REJ-%')
            """)
    boolean existsByPolicyNumberAndIncidentDateAndState(
            @Param("policyNumber") String policyNumber,
            @Param("incidentDate") LocalDate incidentDate,
            @Param("state") String state);

    /**
     * Records an adjuster decision only if the claim is still in a reviewable status.
     * Done as one conditional UPDATE so two concurrent reviews cannot both record a final decision.
     *
     * @return 1 if applied, 0 if the claim was not in a reviewable status
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update ClaimEntity c
               set c.status = :decision, c.adjusterDecision = :decision, c.adjusterId = :adjusterId,
                   c.adjusterNotes = :notes, c.reviewedAt = :reviewedAt, c.updatedAt = :reviewedAt
             where c.claimId = :claimId and c.status in :reviewable
            """)
    int applyReview(@Param("claimId") String claimId,
                    @Param("decision") String decision,
                    @Param("adjusterId") String adjusterId,
                    @Param("notes") String notes,
                    @Param("reviewedAt") Instant reviewedAt,
                    @Param("reviewable") Collection<String> reviewable);

    /** Adjuster queue: claims in the given statuses, oldest first. */
    Page<ClaimEntity> findByStatusInOrderByCreatedAtAsc(Collection<String> statuses, Pageable pageable);
}
