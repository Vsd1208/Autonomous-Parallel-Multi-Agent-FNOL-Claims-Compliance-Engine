package com.guidewire.fnol.api;

import com.guidewire.fnol.api.persistence.EventInboxEntity;
import com.guidewire.fnol.api.persistence.EventInboxRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The race a stack run found: a row claimed but refused a thread looked taken, with nobody working on it, until its lease ran out. */
class EventInboxWorkerDispatchTest {

    private final EventInboxService inbox = mock(EventInboxService.class);
    private final ClaimEventController events = mock(ClaimEventController.class);

    private EventInboxWorker worker(int concurrency) {
        return new EventInboxWorker(inbox, mock(EventInboxRepository.class), events, true, concurrency);
    }

    private static EventInboxEntity row(String id) {
        EventInboxEntity e = new EventInboxEntity();
        e.id = id;
        e.eventId = id;
        e.aggregateId = "CLM-" + id;
        e.payload = "{\"id\":\"" + id + "\",\"type\":\"claim.submitted\",\"source\":\"s\",\"subject\":\"CLM-" + id + "\",\"data\":{}}";
        return e;
    }

    @Test
    @DisplayName("Two rows due at once with room for two: both run, and neither is refused")
    void twoDueTogetherBothRun() {
        EventInboxWorker worker = worker(2);
        when(inbox.claimDue(anyString(), anyInt())).thenReturn(List.of(row("a"), row("b")));

        worker.poll();

        verify(inbox, timeout(3000)).complete("a");
        verify(inbox, timeout(3000)).complete("b");
        verify(inbox, never()).giveBack(anyString());
        worker.shutdown();
    }

    @Test
    @DisplayName("It claims no more than it has room for, counting the job still running")
    void claimsOnlyWhatItCanRun() throws Exception {
        EventInboxWorker worker = worker(2);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch running = new CountDownLatch(1);
        doAnswer(i -> {
            running.countDown();
            release.await(5, TimeUnit.SECONDS);
            return null;
        }).when(events).analyse(anyString(), any(), any(), anyBoolean());
        when(inbox.claimDue(anyString(), anyInt())).thenReturn(List.of(row("a"))).thenReturn(List.of());

        worker.poll();
        assertThat(running.await(3, TimeUnit.SECONDS)).isTrue();
        worker.poll();

        verify(inbox).claimDue(anyString(), org.mockito.ArgumentMatchers.eq(2));
        verify(inbox).claimDue(anyString(), org.mockito.ArgumentMatchers.eq(1));
        release.countDown();
        worker.shutdown();
    }

    @Test
    @DisplayName("With every slot busy it claims nothing, and a finished job frees its slot, even after a failure")
    void fullThenFreed() throws Exception {
        EventInboxWorker worker = worker(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch running = new CountDownLatch(1);
        doAnswer(i -> {
            running.countDown();
            release.await(5, TimeUnit.SECONDS);
            throw new IllegalStateException("analysis failed");
        }).when(events).analyse(anyString(), any(), any(), anyBoolean());
        when(inbox.claimDue(anyString(), anyInt())).thenReturn(List.of(row("a"))).thenReturn(List.of());

        worker.poll();
        assertThat(running.await(3, TimeUnit.SECONDS)).isTrue();
        worker.poll();
        verify(inbox).claimDue(anyString(), anyInt());

        release.countDown();
        verify(inbox, timeout(3000)).failed(org.mockito.ArgumentMatchers.eq("a"), anyString());
        Thread.sleep(100);
        worker.poll();
        verify(inbox, timeout(3000).times(2)).claimDue(anyString(), anyInt());
        worker.shutdown();
    }

    @Test
    @DisplayName("A row that cannot start goes straight back to the queue")
    void rejectedRowGoesBack() {
        EventInboxWorker worker = worker(2);
        worker.shutdown();
        when(inbox.claimDue(anyString(), anyInt())).thenReturn(List.of(row("a")));

        worker.poll();

        verify(inbox).giveBack("a");
        verify(inbox, never()).complete(anyString());
    }
}
