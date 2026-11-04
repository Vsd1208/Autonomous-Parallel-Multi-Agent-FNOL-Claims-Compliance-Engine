package com.guidewire.fnol.api;

import com.guidewire.fnol.agents.AuditTrailAgent;
import com.guidewire.fnol.api.persistence.PortalAccessTokenEntity;
import com.guidewire.fnol.api.persistence.PortalAccessTokenRepository;
import com.guidewire.fnol.api.persistence.PortalAttemptEntity;
import com.guidewire.fnol.api.persistence.PortalAttemptRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Protects the public routes: a limit on failed attempts, kept in the database so it holds across instances without new
 * infrastructure, and short-lived, signed, revocable claim-access tokens. Security events are logged with hashes, never a claim number,
 * policy number or address. Under heavy concurrent attack a count can be slightly off; this is a brute-force limit, not a precise meter.
 */
@Service
class PortalProtection {

    /** Too many failed attempts. Answered as 429. */
    static class TooManyAttemptsException extends RuntimeException {
        final long retryAfterSeconds;

        TooManyAttemptsException(long retryAfterSeconds) {
            super("Too many attempts. Please try again later.");
            this.retryAfterSeconds = retryAfterSeconds;
        }
    }

    private static final Logger security = LoggerFactory.getLogger("SECURITY");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final PortalAttemptRepository attempts;
    private final PortalAccessTokenRepository tokens;
    private final int maxFailures;
    private final Duration window;
    private final Duration tokenLife;
    private final byte[] secret;

    PortalProtection(PortalAttemptRepository attempts, PortalAccessTokenRepository tokens,
                     @Value("${fnol.portal.max-failures:10}") int maxFailures,
                     @Value("${fnol.portal.window-seconds:900}") long windowSeconds,
                     @Value("${fnol.portal.token-minutes:30}") long tokenMinutes,
                     @Value("${fnol.portal.token-secret:}") String tokenSecret) {
        this.attempts = attempts;
        this.tokens = tokens;
        this.maxFailures = maxFailures;
        this.window = Duration.ofSeconds(windowSeconds);
        this.tokenLife = Duration.ofMinutes(tokenMinutes);
        if (tokenSecret == null || tokenSecret.isBlank()) {
            // Tokens then last only until a restart, and do not work across instances. Safe, and stated in docs/SECURITY.md.
            byte[] generated = new byte[32];
            RANDOM.nextBytes(generated);
            this.secret = generated;
        } else {
            this.secret = tokenSecret.getBytes(StandardCharsets.UTF_8);
        }
    }

    /** @throws TooManyAttemptsException while this key is blocked */
    void check(String key) {
        Instant now = Instant.now();
        Optional<PortalAttemptEntity> found = attempts.findById(AuditTrailAgent.sha256(key));
        if (found.isPresent() && found.get().blockedUntil != null && found.get().blockedUntil.isAfter(now)) {
            event("BLOCKED", key);
            throw new TooManyAttemptsException(Duration.between(now, found.get().blockedUntil).getSeconds() + 1);
        }
    }

    void failed(String key) {
        Instant now = Instant.now();
        String hash = AuditTrailAgent.sha256(key);
        PortalAttemptEntity a = attempts.findById(hash).orElseGet(() -> {
            PortalAttemptEntity fresh = new PortalAttemptEntity();
            fresh.keyHash = hash;
            fresh.windowStart = now;
            return fresh;
        });
        if (a.windowStart.plus(window).isBefore(now)) {
            a.windowStart = now;
            a.failures = 0;
            a.blockedUntil = null;
        }
        a.failures++;
        if (a.failures >= maxFailures) {
            a.blockedUntil = now.plus(window);
            event("THRESHOLD_REACHED", key);
        }
        attempts.save(a);
        event("FAILED_ATTEMPT", key);
    }

    void succeeded(String key) {
        attempts.deleteById(AuditTrailAgent.sha256(key));
    }

    /** A random id and an expiry, signed. It says nothing about the claim: that link lives only in the database. */
    record Issued(String token, Instant expiresAt) {}

    Issued issue(String claimId) {
        byte[] id = new byte[24];
        RANDOM.nextBytes(id);
        Instant expires = Instant.now().plus(tokenLife);
        String body = Base64.getUrlEncoder().withoutPadding().encodeToString(id) + "." + expires.getEpochSecond();
        String token = body + "." + sign(body);
        PortalAccessTokenEntity e = new PortalAccessTokenEntity();
        e.tokenHash = AuditTrailAgent.sha256(token);
        e.claimId = claimId;
        e.expiresAt = expires;
        e.createdAt = Instant.now();
        tokens.save(e);
        return new Issued(token, expires);
    }

    /** The claim this token gives access to, if it is genuine, in date, not revoked and for this claim. */
    boolean allows(String token, String claimId) {
        if (token == null) {
            return false;
        }
        int dot = token.lastIndexOf('.');
        if (dot < 0 || !constantTimeEquals(sign(token.substring(0, dot)), token.substring(dot + 1))) {
            event("BAD_TOKEN", token);
            return false;
        }
        return tokens.findById(AuditTrailAgent.sha256(token))
                .filter(t -> t.revokedAt == null && t.expiresAt.isAfter(Instant.now()) && t.claimId.equals(claimId))
                .isPresent();
    }

    /** Revokes a token. True if there was one to revoke. */
    boolean revoke(String token) {
        return tokens.findById(AuditTrailAgent.sha256(token == null ? "" : token)).map(t -> {
            t.revokedAt = Instant.now();
            tokens.save(t);
            event("TOKEN_REVOKED", token);
            return true;
        }).orElse(false);
    }

    private String sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return java.security.MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static void event(String what, String key) {
        security.warn("event={} keyHash={}", what, AuditTrailAgent.sha256(key).substring(0, 16));
    }
}
