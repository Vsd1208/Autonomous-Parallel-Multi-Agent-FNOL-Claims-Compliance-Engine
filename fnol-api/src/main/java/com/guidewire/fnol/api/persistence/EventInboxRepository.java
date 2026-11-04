package com.guidewire.fnol.api.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface EventInboxRepository extends JpaRepository<EventInboxEntity, String> {

    Optional<EventInboxEntity> findByEventId(String eventId);

    boolean existsByAggregateIdAndStatusIn(String aggregateId, Collection<String> statuses);

    long countByStatus(String status);

    /** One column at a time: the two branches finish in parallel, and neither may overwrite the other. */
    @Modifying
    @Transactional
    @Query("update EventInboxEntity e set e.branchAJson = :json where e.id = :id")
    int saveBranchA(@Param("id") String id, @Param("json") String json);

    @Modifying
    @Transactional
    @Query("update EventInboxEntity e set e.branchBJson = :json where e.id = :id")
    int saveBranchB(@Param("id") String id, @Param("json") String json);

    List<EventInboxEntity> findByStatusOrderByReceivedAtAsc(String status, Pageable page);

    /**
     * Work that is due, or whose worker's lease ran out. SKIP LOCKED means another instance claiming at the same moment passes over these
     * rows rather than waiting and then taking them too.
     */
    @Query(value = """
            SELECT * FROM fnol_event_inbox
             WHERE (status IN ('PENDING', 'RETRY_PENDING') AND next_attempt_at <= :now)
                OR (status = 'PROCESSING' AND lease_expires_at < :now)
             ORDER BY received_at
             LIMIT :limit
             FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<EventInboxEntity> lockDue(@Param("now") Instant now, @Param("limit") int limit);
}
