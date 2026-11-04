package com.guidewire.fnol.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guidewire.fnol.api.observability.CorrelationIdFilter;
import com.guidewire.fnol.api.persistence.EventInboxEntity;
import com.guidewire.fnol.api.persistence.EventInboxRepository;
import com.guidewire.fnol.common.Models.BranchAResult;
import com.guidewire.fnol.common.Models.BranchBResult;
import com.guidewire.fnol.common.Models.ClaimEvent;
import com.guidewire.fnol.orchestrator.BranchCheckpoint;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Takes accepted events from the inbox and analyses them. A crash at any point leaves the row to come round again when its lease runs out,
 * and finished branch results are kept, so a retry runs only what did not finish.
 */
@Component
class EventInboxWorker {

    private static final Logger log = LoggerFactory.getLogger(EventInboxWorker.class);

    private final EventInboxService inbox;
    private final EventInboxRepository rows;
    private final ClaimEventController events;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final boolean enabled;
    private final String owner = EventInboxService.newOwner();
    private final ThreadPoolExecutor executor;

    EventInboxWorker(EventInboxService inbox, EventInboxRepository rows, ClaimEventController events,
                     @Value("${fnol.inbox.worker-enabled:true}") boolean enabled,
                     @Value("${fnol.inbox.concurrency:4}") int concurrency) {
        this.inbox = inbox;
        this.rows = rows;
        this.events = events;
        this.enabled = enabled;
        this.executor = new ThreadPoolExecutor(concurrency, concurrency, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1), r -> {
            Thread t = new Thread(r, "claim-event");
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }

    @Scheduled(fixedDelayString = "${fnol.inbox.poll-ms:1000}")
    void poll() {
        if (!enabled) {
            return;
        }
        int free = executor.getMaximumPoolSize() - executor.getActiveCount();
        if (free <= 0) {
            return;
        }
        try {
            for (EventInboxEntity row : inbox.claimDue(owner, free)) {
                executor.execute(() -> process(row));
            }
        } catch (RuntimeException e) {
            // The database may be away. Nothing is lost: the rows are still there, and this runs again shortly.
            log.warn("[Inbox] Could not claim work: {}", e.getMessage());
        }
    }

    /** Claims and processes what is due on this thread. A worker in another JVM would do the same. */
    int runOnce(String workerName) {
        var claimed = inbox.claimDue(workerName, 10);
        claimed.forEach(this::process);
        return claimed.size();
    }

    void process(EventInboxEntity row) {
        // The event's ID traces every attempt, so a retry can be followed across restarts.
        MDC.put(CorrelationIdFilter.CORRELATION_ID, row.eventId);
        MDC.put("eventId", row.eventId);
        MDC.put(CorrelationIdFilter.CLAIM_ID, row.aggregateId);
        try {
            ClaimEvent event = mapper.readValue(row.payload, ClaimEvent.class);
            LocalDate notice = event.time() == null ? LocalDate.now() : LocalDate.ofInstant(event.time(), ZoneId.systemDefault());
            events.analyse(event.subject(), notice, checkpoint(row), row.attemptCount <= 1, inbox.lastAttempt(row));
            inbox.complete(row.id);
        } catch (ClaimEventController.RetryLaterException e) {
            inbox.failed(row.id, e.getMessage());
        } catch (Exception e) {
            log.error("[Inbox] Event {} failed unexpectedly", row.eventId, e);
            inbox.failed(row.id, e.getClass().getSimpleName());
        } finally {
            MDC.clear();
        }
    }

    private BranchCheckpoint checkpoint(EventInboxEntity row) {
        return new BranchCheckpoint() {
            @Override
            public BranchAResult savedA() {
                return read(rows.findById(row.id).map(r -> r.branchAJson).orElse(null), BranchAResult.class);
            }

            @Override
            public BranchBResult savedB() {
                return read(rows.findById(row.id).map(r -> r.branchBJson).orElse(null), BranchBResult.class);
            }

            @Override
            public void saveA(BranchAResult a) {
                save(a, null);
            }

            @Override
            public void saveB(BranchBResult b) {
                save(null, b);
            }

            private void save(BranchAResult a, BranchBResult b) {
                try {
                    inbox.saveBranches(row.id, a == null ? null : mapper.writeValueAsString(a), b == null ? null : mapper.writeValueAsString(b));
                } catch (Exception e) {
                    log.warn("[Inbox] A finished branch could not be saved for event {}; a retry will run it again", row.eventId);
                }
            }
        };
    }

    private <T> T read(String json, Class<T> type) {
        if (json == null) {
            return null;
        }
        try {
            return mapper.readValue(json, type);
        } catch (Exception e) {
            log.warn("[Inbox] A saved {} could not be read; running it again", type.getSimpleName());
            return null;
        }
    }
}
