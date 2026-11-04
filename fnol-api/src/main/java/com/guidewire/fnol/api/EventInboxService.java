package com.guidewire.fnol.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.guidewire.fnol.agents.AuditTrailAgent;
import com.guidewire.fnol.api.persistence.EventInboxEntity;
import com.guidewire.fnol.api.persistence.EventInboxRepository;
import com.guidewire.fnol.common.Models.ClaimEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** The durable inbox: what was accepted, who is working on it, what happens next. Every state change commits on its own. */
@Service
public class EventInboxService {

    private com.guidewire.fnol.common.ProcessingObserver observer = com.guidewire.fnol.common.ProcessingObserver.NONE;

    public enum Accepted { NEW, DUPLICATE, CONFLICT }

    private static final Logger log = LoggerFactory.getLogger(EventInboxService.class);
    private static final Logger security = LoggerFactory.getLogger("SECURITY");
    private static final ObjectMapper CANONICAL = new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    private static final int MAX_ERROR = 500;

    private final EventInboxRepository inbox;
    private final int maxAttempts;
    private final Duration lease;
    private final Duration baseBackoff;
    private final Duration maxBackoff;

    public EventInboxService(EventInboxRepository inbox,
                             @Value("${fnol.inbox.max-attempts:5}") int maxAttempts,
                             @Value("${fnol.inbox.lease-seconds:120}") long leaseSeconds,
                             @Value("${fnol.inbox.backoff-base-seconds:5}") long baseBackoffSeconds,
                             @Value("${fnol.inbox.backoff-max-seconds:600}") long maxBackoffSeconds) {
        this.inbox = inbox;
        this.maxAttempts = maxAttempts;
        this.lease = Duration.ofSeconds(leaseSeconds);
        this.baseBackoff = Duration.ofSeconds(baseBackoffSeconds);
        this.maxBackoff = Duration.ofSeconds(maxBackoffSeconds);
    }

    /** The same event in any field order hashes the same. */
    static String canonicalHash(String body) {
        try {
            JsonNode tree = CANONICAL.readTree(body);
            Object sorted = CANONICAL.treeToValue(tree, Object.class);
            return AuditTrailAgent.sha256(CANONICAL.writeValueAsString(sorted));
        } catch (Exception e) {
            throw new IllegalArgumentException("Not valid JSON", e);
        }
    }

    /** Commits before returning, so the caller's 202 means the event survives a crash. No surrounding transaction: a lost race must not roll anything back. */
    public Accepted accept(ClaimEvent event, String body) {
        String hash = canonicalHash(body);
        Accepted repeat = compareWithExisting(event, hash);
        if (repeat != null) {
            return repeat;
        }
        Instant now = Instant.now();
        EventInboxEntity e = new EventInboxEntity();
        e.id = UUID.randomUUID().toString();
        e.eventId = event.id();
        e.eventType = event.type();
        e.aggregateId = event.subject();
        e.eventTime = event.time();
        e.payload = body;
        e.payloadHash = hash;
        e.status = EventInboxEntity.PENDING;
        e.nextAttemptAt = now;
        e.receivedAt = now;
        e.createdAt = now;
        e.updatedAt = now;
        try {
            inbox.saveAndFlush(e);
        } catch (DataIntegrityViolationException raced) {
            // The same id, delivered twice at once: the unique constraint decided, so compare with the row that won.
            Accepted winner = compareWithExisting(event, hash);
            return winner != null ? winner : Accepted.DUPLICATE;
        }
        return Accepted.NEW;
    }

    private Accepted compareWithExisting(ClaimEvent event, String hash) {
        var existing = inbox.findByEventId(event.id());
        if (existing.isEmpty()) {
            return null;
        }
        if (hash.equals(existing.get().payloadHash)) {
            return Accepted.DUPLICATE;
        }
        security.warn("event=EVENT_HASH_MISMATCH eventId={} claim={}", event.id(), event.subject());
        return Accepted.CONFLICT;
    }

    /** Claims up to {@code limit} due rows under a lease. Two workers can never hold the same row. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<EventInboxEntity> claimDue(String owner, int limit) {
        Instant now = Instant.now();
        List<EventInboxEntity> claimed = new ArrayList<>();
        for (EventInboxEntity e : inbox.lockDue(now, limit)) {
            e.status = EventInboxEntity.PROCESSING;
            e.leaseOwner = owner;
            e.leaseExpiresAt = now.plus(lease);
            e.attemptCount++;
            e.updatedAt = now;
            claimed.add(inbox.save(e));
        }
        return claimed;
    }

    /** A claimed row that never started: as if it had not been claimed, so it does not use up an attempt. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void giveBack(String id) {
        inbox.findById(id).ifPresent(e -> {
            e.status = EventInboxEntity.PENDING;
            e.attemptCount = Math.max(0, e.attemptCount - 1);
            e.leaseOwner = null;
            e.leaseExpiresAt = null;
            e.updatedAt = Instant.now();
            inbox.save(e);
        });
    }

    public boolean lastAttempt(EventInboxEntity e) {
        return e.attemptCount >= maxAttempts;
    }

    public boolean activeFor(String claimId) {
        return inbox.existsByAggregateIdAndStatusIn(claimId, Set.of(EventInboxEntity.PENDING, EventInboxEntity.PROCESSING, EventInboxEntity.RETRY_PENDING));
    }

    /** Finished. The branch results are no longer needed, so they go. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(String id) {
        inbox.findById(id).ifPresent(e -> {
            e.status = EventInboxEntity.COMPLETED;
            e.completedAt = Instant.now();
            e.updatedAt = e.completedAt;
            e.leaseOwner = null;
            e.leaseExpiresAt = null;
            e.lastError = null;
            e.branchAJson = null;
            e.branchBJson = null;
            inbox.save(e);
        });
    }

    /** A failure worth trying again: back off, or dead-letter once the attempts are used up. Branch results stay. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failed(String id, String error) {
        inbox.findById(id).ifPresent(e -> {
            Instant now = Instant.now();
            e.lastError = error == null ? null : error.substring(0, Math.min(error.length(), MAX_ERROR));
            e.leaseOwner = null;
            e.leaseExpiresAt = null;
            e.updatedAt = now;
            if (e.attemptCount >= maxAttempts) {
                e.status = EventInboxEntity.DEAD_LETTER;
                observer.inbox("dead_letter");
                log.error("[Inbox] Event {} for claim {} moved to dead letter after {} attempts: {}", e.eventId, e.aggregateId, e.attemptCount, e.lastError);
            } else {
                e.status = EventInboxEntity.RETRY_PENDING;
                observer.inbox("retry");
                e.nextAttemptAt = now.plus(backoff(e.attemptCount, baseBackoff, maxBackoff));
                log.warn("[Inbox] Event {} for claim {} will retry at {}: {}", e.eventId, e.aggregateId, e.nextAttemptAt, e.lastError);
            }
            inbox.save(e);
        });
    }

    /** Doubling with full jitter, so instances recovering together do not all retry at once. */
    static Duration backoff(int attempt, Duration base, Duration max) {
        long ceiling = Math.min(max.toMillis(), base.toMillis() << Math.min(Math.max(attempt - 1, 0), 20));
        return Duration.ofMillis(ceiling <= 0 ? 0 : ThreadLocalRandom.current().nextLong(ceiling / 2, ceiling + 1));
    }

    public void saveBranches(String id, String branchA, String branchB) {
        if (branchA != null) {
            inbox.saveBranchA(id, branchA);
        }
        if (branchB != null) {
            inbox.saveBranchB(id, branchB);
        }
    }

    /** A person sends a dead-lettered event round again. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean replay(String id) {
        return inbox.findById(id).filter(e -> EventInboxEntity.DEAD_LETTER.equals(e.status)).map(e -> {
            e.status = EventInboxEntity.RETRY_PENDING;
            e.attemptCount = 0;
            e.nextAttemptAt = Instant.now();
            e.updatedAt = e.nextAttemptAt;
            inbox.save(e);
            return true;
        }).orElse(false);
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setProcessingObserver(com.guidewire.fnol.common.ProcessingObserver observer) {
        this.observer = observer;
    }

    /** Queue depth by status, for operations. */
    public java.util.Map<String, Long> depth() {
        java.util.Map<String, Long> out = new java.util.LinkedHashMap<>();
        for (String s : List.of(EventInboxEntity.PENDING, EventInboxEntity.PROCESSING, EventInboxEntity.RETRY_PENDING, EventInboxEntity.DEAD_LETTER, EventInboxEntity.COMPLETED)) {
            out.put(s, inbox.countByStatus(s));
        }
        return out;
    }

    static String newOwner() {
        return "worker-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
