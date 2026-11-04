package com.guidewire.fnol.orchestrator;

import com.guidewire.fnol.common.Models.BranchAResult;
import com.guidewire.fnol.common.Models.BranchBResult;

/** Keeps a finished branch's result between attempts, so a retry runs only what did not finish. */
public interface BranchCheckpoint {

    BranchCheckpoint NONE = new BranchCheckpoint() {
    };

    default BranchAResult savedA() {
        return null;
    }

    default BranchBResult savedB() {
        return null;
    }

    default void saveA(BranchAResult result) {
    }

    default void saveB(BranchBResult result) {
    }
}
