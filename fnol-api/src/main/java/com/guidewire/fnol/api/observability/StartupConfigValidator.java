package com.guidewire.fnol.api.observability;

import jakarta.annotation.PostConstruct;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * With {@code fnol.environment=production}, refuses to start on settings only a demo should have. It names the setting and never its value.
 * A development or demo stack is not checked. Passing this does not make a deployment ready; it only stops the known bad cases.
 */
@Component
public class StartupConfigValidator {

    static final int MINIMUM_PASSWORD_ITERATIONS = 100_000;
    private static final Set<String> KNOWN_WEAK = Set.of("demo-adjuster-token", "demo-event-secret", "local-dev-event-secret", "demo-password", "changeme", "fnol");

    private final Environment env;

    public StartupConfigValidator(Environment env) {
        this.env = env;
    }

    @PostConstruct
    void validate() {
        List<String> problems = problems(env);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Not starting with fnol.environment=production: " + String.join("; ", problems));
        }
    }

    static List<String> problems(Environment env) {
        if (!"production".equalsIgnoreCase(env.getProperty("fnol.environment", "development"))) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        secret(env, "fnol.security.adjuster-token", out);
        secret(env, "fnol.claims-events.secret", out);
        secret(env, "fnol.portal.token-secret", out);
        secret(env, "spring.datasource.password", out);
        String users = env.getProperty("fnol.security.users", "");
        if (KNOWN_WEAK.stream().anyMatch(weak -> users.contains("|" + weak + "|"))) {
            out.add("fnol.security.users holds a demo password");
        }
        if (env.getProperty("fnol.security.password-iterations", Integer.class, 210_000) < MINIMUM_PASSWORD_ITERATIONS) {
            out.add("fnol.security.password-iterations is below " + MINIMUM_PASSWORD_ITERATIONS);
        }
        if (!env.acceptsProfiles(Profiles.of("guidewire", "guidewire-cloud-claims"))) {
            out.add("the claims system is the mock: activate a real ClaimCenter profile");
        }
        if (!env.acceptsProfiles(Profiles.of("guidewire", "guidewire-cloud-policy"))) {
            out.add("the policy system is the mock: activate a real PolicyCenter profile");
        }
        if (!"false".equalsIgnoreCase(env.getProperty("gemini.vision.enabled", "true")) && env.getProperty("gemini.api-key", "").isBlank()) {
            out.add("gemini.api-key is empty while Gemini vision is enabled, so every claim would fall back");
        }
        return out;
    }

    private static void secret(Environment env, String key, List<String> out) {
        String value = env.getProperty(key, "");
        if (value.isBlank()) {
            out.add(key + " is not set");
        } else if (KNOWN_WEAK.contains(value)) {
            out.add(key + " is a demo value");
        }
    }
}
