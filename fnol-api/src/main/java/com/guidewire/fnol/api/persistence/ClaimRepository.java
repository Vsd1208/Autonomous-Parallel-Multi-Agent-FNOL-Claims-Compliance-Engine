package com.guidewire.fnol.api.persistence;
import org.springframework.data.jpa.repository.*;
import java.time.LocalDate;

public interface ClaimRepository extends JpaRepository<ClaimEntity, String> {
    /**
     * DB-backed duplicate FNOL check — survives server restarts.
     * Returns true if a claim with the same policy + date + state already exists.
     */
    boolean existsByPolicyNumberAndIncidentDateAndState(
            String policyNumber, LocalDate incidentDate, String state);
}
