package com.guidewire.fnol.api;

import com.guidewire.fnol.api.persistence.EventInboxEntity;
import com.guidewire.fnol.api.persistence.EventInboxRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Operations on the event inbox, for supervisors: what is queued, what failed, and sending a failed event round again. */
@RestController
@RequestMapping("/api/v1/admin/event-inbox")
class AdminEventInboxController {

    private final EventInboxService inbox;
    private final EventInboxRepository rows;

    AdminEventInboxController(EventInboxService inbox, EventInboxRepository rows) {
        this.inbox = inbox;
        this.rows = rows;
    }

    @GetMapping
    Map<String, Object> list(@RequestParam(name = "status", defaultValue = EventInboxEntity.DEAD_LETTER) String status) {
        List<EventInboxItem> items = rows.findByStatusOrderByReceivedAtAsc(status, PageRequest.of(0, 100)).stream().map(EventInboxItem::of).toList();
        return Map.of("depth", inbox.depth(), "items", items);
    }

    /** What an operator needs. Not the event's payload or the saved branch results, which hold claim content. */
    record EventInboxItem(String id, String eventId, String eventType, String aggregateId, String status, int attemptCount, String lastError,
                          java.time.Instant nextAttemptAt, java.time.Instant receivedAt) {
        static EventInboxItem of(EventInboxEntity e) {
            return new EventInboxItem(e.id, e.eventId, e.eventType, e.aggregateId, e.status, e.attemptCount, e.lastError, e.nextAttemptAt, e.receivedAt);
        }
    }

    @PostMapping("/{id}/replay")
    ResponseEntity<?> replay(@PathVariable("id") String id) {
        return inbox.replay(id) ? ResponseEntity.accepted().body(Map.of("status", EventInboxEntity.RETRY_PENDING))
                : ResponseEntity.status(409).body(Map.of("error", "NOT_DEAD_LETTER", "message", "Only a dead-lettered event can be replayed"));
    }
}
