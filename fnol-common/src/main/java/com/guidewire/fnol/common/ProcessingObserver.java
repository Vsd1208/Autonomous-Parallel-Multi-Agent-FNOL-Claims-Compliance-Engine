package com.guidewire.fnol.common;

/** What the processing code reports for operations: durations and outcomes only, never claim content. Does nothing unless metrics are wired in. */
public interface ProcessingObserver {

    ProcessingObserver NONE = new ProcessingObserver() {
    };

    /** A branch finished: operational or legal. */
    default void branch(String branch, long millis, boolean failed) {
    }

    /** A call to an external model finished. Outcome: OK, or why not. */
    default void provider(String provider, long millis, String outcome) {
    }

    /** An inbox event was retried or moved to the dead letter. */
    default void inbox(String outcome) {
    }
}
