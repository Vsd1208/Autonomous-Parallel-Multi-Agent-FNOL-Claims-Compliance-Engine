package com.guidewire.fnol.api;

import com.guidewire.fnol.api.persistence.ClaimRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Two-layer idempotency guard for duplicate FNOL submissions:
 * <ol>
 *   <li><b>In-memory set</b> — fast, concurrent protection for requests arriving
 *       simultaneously during the same JVM session (race-condition guard).</li>
 *   <li><b>DB-backed check</b> via {@link ClaimRepository} — survives server restarts
 *       and protects against re-submission across deployments.</li>
 * </ol>
 * HTTP 409 CONFLICT is returned for any duplicate detected at either layer.
 */
@Component
public class IdempotencyStore {

    private final ClaimRepository claimRepository;
    private final Set<String> inFlightKeys = ConcurrentHashMap.newKeySet();

    public IdempotencyStore(ClaimRepository claimRepository) {
        this.claimRepository = claimRepository;
    }

    /**
     * Returns {@code true} if this FNOL is new and the slot was successfully acquired.
     * Returns {@code false} (duplicate) if the same policy+date+state already exists
     * in-flight OR persisted in the DB.
     */
    public boolean tryAcquire(String policyNumber, LocalDate incidentDate, String state) {
        // Layer 1: fast in-memory guard for concurrent in-flight requests
        String key = buildKey(policyNumber, incidentDate.toString(), state);
        if (!inFlightKeys.add(key)) {
            return false;   // concurrent duplicate
        }
        // Layer 2: DB-backed check — catches duplicates across restarts
        if (claimRepository.existsByPolicyNumberAndIncidentDateAndState(policyNumber, incidentDate, state)) {
            inFlightKeys.remove(key); // release in-memory slot; DB already has it
            return false;
        }
        return true;
    }

    /** Release the in-memory slot on processing failure so the claim can be retried. */
    public void release(String policyNumber, String incidentDate, String state) {
        inFlightKeys.remove(buildKey(policyNumber, incidentDate, state));
    }

    private String buildKey(String policyNumber, String incidentDate, String state) {
        return policyNumber + "|" + incidentDate + "|" + state;
    }
}