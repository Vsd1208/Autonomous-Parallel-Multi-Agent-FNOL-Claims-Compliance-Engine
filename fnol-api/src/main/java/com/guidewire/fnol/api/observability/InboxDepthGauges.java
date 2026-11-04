package com.guidewire.fnol.api.observability;

import com.guidewire.fnol.api.EventInboxService;
import com.guidewire.fnol.api.persistence.EventInboxEntity;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.List;

/** The event inbox's depth by status, including the dead letter. Read when scraped; with the database away the value is not a number, not a false zero. */
@Component
public class InboxDepthGauges {

    public InboxDepthGauges(MeterRegistry registry, EventInboxService inbox) {
        for (String status : List.of(EventInboxEntity.PENDING, EventInboxEntity.PROCESSING, EventInboxEntity.RETRY_PENDING, EventInboxEntity.DEAD_LETTER)) {
            Gauge.builder("fnol.inbox.depth", () -> {
                try {
                    return inbox.depth().getOrDefault(status, 0L);
                } catch (RuntimeException e) {
                    return Double.NaN;
                }
            }).tag("status", status).register(registry);
        }
    }
}
