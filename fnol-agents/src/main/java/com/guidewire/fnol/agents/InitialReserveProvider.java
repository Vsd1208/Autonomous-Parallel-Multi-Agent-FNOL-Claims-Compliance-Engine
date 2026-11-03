package com.guidewire.fnol.agents;

import java.math.BigDecimal;
import java.util.Optional;

/** The initial reserve for a kind of loss, before the AI adjustment and the policy's caps. */
public interface InitialReserveProvider {

    /** One row of the table: its amount, and whether a published figure or a placeholder stands behind it. */
    record InitialReserve(String row, BigDecimal amount, String provenance, String source) {}

    /** Empty when the table has no such row: the caller must send the claim to a person, never assume zero. */
    Optional<InitialReserve> lookup(String lineOfBusiness, String coverage, String severity);
}
