package com.guidewire.fnol.agents;

import com.guidewire.fnol.common.Models.AuditRecord;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

@Component
public class AuditTrailAgent {

    public AuditRecord record(String claimId, String agentName, Object input, Object output, String status) {
        return new AuditRecord(UUID.randomUUID().toString(), claimId, agentName, Instant.now(),
                sha256(input), sha256(output), status);
    }

    /** Hex SHA-256 of {@code String.valueOf(o)}. Callers must pass sanitized objects, never raw PII. */
    public static String sha256(Object o) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(String.valueOf(o).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
