package com.guidewire.fnol.orchestrator;

import com.guidewire.fnol.agents.MockVisionProvider;
import com.guidewire.fnol.agents.VisionProvider;
import com.guidewire.fnol.common.Models.DamageAssessment;
import com.guidewire.fnol.common.Models.FNOLPayload;
import com.guidewire.fnol.common.ProcessingObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BranchTraceTest {

    private final List<String> reports = new CopyOnWriteArrayList<>();
    private final ProcessingObserver observer = new ProcessingObserver() {
        @Override
        public void branch(String branch, long millis, boolean failed) {
            assertThat(millis).isNotNegative();
            reports.add(branch + ":" + (failed ? "failed" : "ok"));
        }
    };

    @AfterEach
    void clear() {
        MDC.clear();
    }

    private FNOLOrchestrator orchestrator(VisionProvider vision) {
        OrchestratorFixture fixture = new OrchestratorFixture();
        fixture.vision = vision;
        FNOLOrchestrator o = fixture.build();
        o.setProcessingObserver(observer);
        return o;
    }

    private static FNOLPayload payload() {
        return OrchestratorFixture.payload("POL-AUTO-112233", "Avery Johnson", "Scraped a pole in a parking garage.");
    }

    @Test
    @DisplayName("The correlation ID reaches the branch threads, and is not left on them afterwards")
    void correlationIdReachesBranches() {
        AtomicReference<String> seen = new AtomicReference<>();
        AtomicReference<Thread> thread = new AtomicReference<>();
        VisionProvider recording = new VisionProvider() {
            final MockVisionProvider real = new MockVisionProvider();

            @Override
            public DamageAssessment assess(FNOLPayload p) {
                seen.set(MDC.get("correlationId"));
                thread.set(Thread.currentThread());
                return real.assess(p);
            }
        };
        MDC.put("correlationId", "trace-1");

        orchestrator(recording).process(payload());

        assertThat(seen.get()).isEqualTo("trace-1");
        assertThat(thread.get()).isNotEqualTo(Thread.currentThread());
    }

    @Test
    @DisplayName("Both branches report their time and outcome")
    void branchesReport() {
        orchestrator(new MockVisionProvider()).process(payload());
        assertThat(reports).containsExactlyInAnyOrder("operational:ok", "legal:ok");
    }

    @Test
    @DisplayName("A failing branch is counted as failed")
    void failureReported() {
        VisionProvider broken = p -> {
            throw new IllegalStateException("model down");
        };
        assertThatThrownBy(() -> orchestrator(broken).process(payload())).isInstanceOf(RuntimeException.class);
        assertThat(reports).contains("operational:failed");
    }
}
