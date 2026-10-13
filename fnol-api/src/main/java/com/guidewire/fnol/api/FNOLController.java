
package com.guidewire.fnol.api;

import com.guidewire.fnol.common.Models.*;
import com.guidewire.fnol.common.ProcessingTimeoutException;
import com.guidewire.fnol.common.ComplianceBlockException;
import com.guidewire.fnol.orchestrator.*;
import com.guidewire.fnol.api.persistence.*;

import jakarta.servlet.http.*;
import jakarta.validation.*;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

import java.time.*;
import java.util.*;

@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/api/v1")
public class FNOLController {

    private final FNOLOrchestrator orchestrator;
    private final ClaimRepository claims;
    private final AuditRepository audits;
    private final IdempotencyStore idempotencyStore;

    public FNOLController(
            FNOLOrchestrator orchestrator,
            ClaimRepository claims,
            AuditRepository audits,
            IdempotencyStore idempotencyStore) {
        this.orchestrator = orchestrator;
        this.claims = claims;
        this.audits = audits;
        this.idempotencyStore = idempotencyStore;
    }

    @GetMapping("/health")
    Map<String, String> health() {
        return Map.of("status", "UP");
    }

    @PostMapping("/fnol/intake")
    FNOLResponse intake(@Valid @RequestBody FNOLPayload payload) {

        String policyNumber = payload.policyNumber();
        String incidentDate = String.valueOf(payload.incidentDate());
        String state = payload.state();

        // Check whether this FNOL has already been processed
        if (!idempotencyStore.tryAcquire(
                policyNumber, incidentDate, state)) {
            throw new DuplicateFNOLException(
                    "This FNOL has already been submitted");
        }

        try {
            // Process the FNOL
            FNOLResponse response = orchestrator.process(payload);

            // Save the claim and audit records
            claims.save(ClaimEntity.from(response, payload));
            response.branchB().audit()
                    .forEach(a -> audits.save(AuditEntity.from(a)));

            return response;
        } catch (Exception e) {
            idempotencyStore.release(policyNumber, incidentDate, state);
            throw e;
        }
    }

    @GetMapping("/fnol/status/{claimId}")
    ResponseEntity<?> status(@PathVariable("claimId") String claimId) {
        return claims.findById(claimId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}

class DuplicateFNOLException extends RuntimeException {
    public DuplicateFNOLException(String message) {
        super(message);
    }
}

@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler({
            IllegalArgumentException.class,
            MethodArgumentNotValidException.class
    })
    ResponseEntity<ApiError> bad(
            Exception e, HttpServletRequest r) {
        return ResponseEntity.badRequest().body(
                new ApiError(
                        Instant.now(),
                        400,
                        "VALIDATION_ERROR",
                        e.getMessage(),
                        r.getRequestURI()
                )
        );
    }

    @ExceptionHandler(DuplicateFNOLException.class)
    ResponseEntity<ApiError> duplicate(
            DuplicateFNOLException e, HttpServletRequest r) {
        return ResponseEntity.status(409).body(
                new ApiError(
                        Instant.now(),
                        409,
                        "DUPLICATE_FNOL",
                        e.getMessage(),
                        r.getRequestURI()
                )
        );
    }

    @ExceptionHandler(ComplianceBlockException.class)
    ResponseEntity<ApiError> compliance(
            ComplianceBlockException e, HttpServletRequest r) {
        return ResponseEntity.status(422).body(
                new ApiError(
                        Instant.now(),
                        422,
                        "COMPLIANCE_BLOCK",
                        e.getMessage(),
                        r.getRequestURI()
                )
        );
    }

    @ExceptionHandler({ProcessingTimeoutException.class, org.springframework.web.client.ResourceAccessException.class})
    ResponseEntity<ApiError> timeout(
            Exception e, HttpServletRequest r) {
        return ResponseEntity.status(504).body(
                new ApiError(
                        Instant.now(),
                        504,
                        "PROCESSING_TIMEOUT",
                        "FNOL processing timed out. Claim escalated for manual review.",
                        r.getRequestURI()
                )
        );
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> server(
            Exception e, HttpServletRequest r) {
        e.printStackTrace();
        return ResponseEntity.status(502).body(
                new ApiError(
                        Instant.now(),
                        502,
                        "EXTERNAL_OR_PROCESSING_ERROR",
                        "FNOL processing could not be completed",
                        r.getRequestURI()
                )
        );
    }
}