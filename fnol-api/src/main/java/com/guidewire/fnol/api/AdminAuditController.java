package com.guidewire.fnol.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/** A digest of all audit chain heads, to copy somewhere the database's administrators cannot reach. For supervisors. */
@RestController
@RequestMapping("/api/v1/admin/audit")
class AdminAuditController {

    private final AuditService audit;

    AdminAuditController(AuditService audit) {
        this.audit = audit;
    }

    @GetMapping("/anchor")
    Map<String, String> anchor() {
        return Map.of("digest", audit.anchorDigest(), "at", Instant.now().toString(),
                "note", "Worth keeping only if stored outside this database. Nothing here does that for you.");
    }
}
