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
        List<EventInboxEntity> items = rows.findByStatusOrderByReceivedAtAsc(status, PageRequest.of(0, 100));
        return Map.of("depth", inbox.depth(), "items", items);
    }

    @PostMapping("/{id}/replay")
    ResponseEntity<?> replay(@PathVariable("id") String id) {
        return inbox.replay(id) ? ResponseEntity.accepted().body(Map.of("status", EventInboxEntity.RETRY_PENDING))
                : ResponseEntity.status(409).body(Map.of("error", "NOT_DEAD_LETTER", "message", "Only a dead-lettered event can be replayed"));
    }
}
