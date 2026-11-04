package com.guidewire.fnol.api.observability;

import com.guidewire.fnol.common.ProcessingObserver;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/** Publishes what the processing code reports. Tags are fixed small sets: never a claim, a policy or a person. */
@Component
public class FnolMetrics implements ProcessingObserver {

    private final MeterRegistry registry;

    public FnolMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void branch(String branch, long millis, boolean failed) {
        Timer.builder("fnol.branch.duration").tag("branch", branch).tag("outcome", failed ? "failed" : "ok")
                .publishPercentileHistogram().register(registry).record(millis, TimeUnit.MILLISECONDS);
    }

    @Override
    public void provider(String provider, long millis, String outcome) {
        Timer.builder("fnol.provider.duration").tag("provider", provider).tag("outcome", outcome)
                .publishPercentileHistogram().register(registry).record(millis, TimeUnit.MILLISECONDS);
    }

    @Override
    public void inbox(String outcome) {
        registry.counter("fnol.inbox.events", "outcome", outcome).increment();
    }
}
