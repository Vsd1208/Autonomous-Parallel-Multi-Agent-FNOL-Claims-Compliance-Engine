package com.guidewire.fnol.api.security;

import com.guidewire.fnol.api.persistence.AppUserEntity;
import com.guidewire.fnol.api.persistence.AppUserRepository;
import com.guidewire.fnol.api.persistence.UserSessionEntity;
import com.guidewire.fnol.api.persistence.UserSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sign-in for people: each person has their own account and role, so a decision is recorded under who actually made it
 * rather than a name typed in a form. Passwords are stored as salted PBKDF2 hashes and session tokens only as SHA-256
 * hashes. This is a real login, but a local one; an insurer would plug in its own identity provider (OIDC).
 *
 * <p>Accounts come from {@code fnol.security.users} ({@code id|ROLE|password|Display Name;...}), applied at startup. An
 * account no longer listed is deactivated. With none configured nobody can sign in: it fails closed.
 */
@Service
public class AuthService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    public static final String ADJUSTER = "ADJUSTER";
    public static final String INSPECTOR = "INSPECTOR";
    private static final Set<String> ROLES = Set.of(ADJUSTER, INSPECTOR, ReserveAuthority.SENIOR_ADJUSTER, ReserveAuthority.SUPERVISOR);
    static final Duration SESSION_LENGTH = Duration.ofHours(8);
    static final int MAX_FAILURES = 5;
    static final Duration LOCKOUT = Duration.ofMinutes(15);
    private static final int ITERATIONS = 210_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    public record Session(String token, Actor actor, Instant expiresAt) {}

    private record Failures(int count, Instant until) {}

    private final AppUserRepository users;
    private final UserSessionRepository sessions;
    private final String configured;
    private final Map<String, Failures> failures = new ConcurrentHashMap<>();

    public AuthService(AppUserRepository users, UserSessionRepository sessions,
                       @Value("${fnol.security.users:}") String configured) {
        this.users = users;
        this.sessions = sessions;
        this.configured = configured == null ? "" : configured;
    }

    @Override
    public void run(ApplicationArguments args) {
        Set<String> listed = new java.util.HashSet<>();
        for (String entry : configured.split(";")) {
            if (entry.isBlank()) continue;
            String[] parts = entry.trim().split("\\|", 4);
            if (parts.length != 4 || !ROLES.contains(parts[1].trim()) || parts[2].isEmpty()) {
                // The entry holds a password, so it is not logged.
                throw new IllegalArgumentException("fnol.security.users: each entry must be id|ROLE|password|Display Name, "
                        + "with ROLE one of " + ROLES);
            }
            String id = parts[0].trim();
            listed.add(id);
            AppUserEntity user = users.findById(id).orElseGet(AppUserEntity::new);
            user.username = id;
            user.role = parts[1].trim();
            user.displayName = parts[3].trim();
            user.active = true;
            // A password unchanged in the configuration keeps its hash, so a restart does not re-hash every account.
            if (user.passwordHash == null || !verify(parts[2], user.passwordHash)) {
                user.passwordHash = hash(parts[2]);
            }
            users.save(user);
        }
        for (AppUserEntity user : users.findAll()) {
            if (!listed.contains(user.username) && user.active) {
                user.active = false;
                users.save(user);
                sessions.deleteByUsername(user.username);
            }
        }
        log.info("[Auth] {} account(s) active", listed.size());
    }

    /** @throws LockedOutException after too many failures; empty when the name or password is wrong */
    public Optional<Session> login(String username, String password) {
        String key = username == null ? "" : username.trim().toUpperCase(Locale.ROOT);
        Failures f = failures.get(key);
        if (f != null && f.until() != null && f.until().isAfter(Instant.now())) {
            throw new LockedOutException();
        }
        // Same work whether or not the account exists, so timing does not reveal which names are real.
        Optional<AppUserEntity> user = users.findById(username == null ? "" : username.trim()).filter(u -> u.active);
        boolean ok = verify(password == null ? "" : password, user.map(u -> u.passwordHash).orElse(DUMMY_HASH));
        if (user.isEmpty() || !ok) {
            int count = f == null ? 1 : f.count() + 1;
            failures.put(key, new Failures(count, count >= MAX_FAILURES ? Instant.now().plus(LOCKOUT) : null));
            log.warn("[Auth] Failed sign-in");
            return Optional.empty();
        }
        failures.remove(key);
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        UserSessionEntity s = new UserSessionEntity();
        s.tokenHash = sha256(token);
        s.username = user.get().username;
        s.createdAt = Instant.now();
        s.expiresAt = s.createdAt.plus(SESSION_LENGTH);
        sessions.save(s);
        log.info("[Auth] {} signed in", s.username);
        return Optional.of(new Session(token, Actor.of(user.get()), s.expiresAt));
    }

    /** The person a session token belongs to, if the session is current and the account active. */
    public Optional<Actor> resolve(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        return sessions.findById(sha256(token))
                .filter(s -> s.expiresAt.isAfter(Instant.now()))
                .flatMap(s -> users.findById(s.username))
                .filter(u -> u.active)
                .map(Actor::of);
    }

    public void logout(String token) {
        if (token != null) sessions.deleteById(sha256(token));
    }

    public static class LockedOutException extends RuntimeException {
        public LockedOutException() {
            super("Too many failed sign-ins. Try again in " + LOCKOUT.toMinutes() + " minutes.");
        }
    }

    private static final String DUMMY_HASH = hash("not-a-real-password");

    static String hash(String password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return "pbkdf2$" + ITERATIONS + "$" + HexFormat.of().formatHex(salt) + "$"
                + HexFormat.of().formatHex(derive(password, salt, ITERATIONS));
    }

    static boolean verify(String password, String stored) {
        String[] p = stored.split("\\$");
        if (p.length != 4) return false;
        byte[] expected = HexFormat.of().parseHex(p[3]);
        return MessageDigest.isEqual(expected, derive(password, HexFormat.of().parseHex(p[2]), Integer.parseInt(p[1])));
    }

    private static byte[] derive(String password, byte[] salt, int iterations) {
        char[] chars = password.toCharArray();
        PBEKeySpec spec = new PBEKeySpec(chars, salt, iterations, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 is not available", e);
        } finally {
            spec.clearPassword();
            Arrays.fill(chars, '\0');
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
