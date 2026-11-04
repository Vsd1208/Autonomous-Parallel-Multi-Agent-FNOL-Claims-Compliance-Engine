package com.guidewire.fnol.api.observability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class StartupConfigValidatorTest {

    private static MockEnvironment production() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("fnol.environment", "production")
                .withProperty("fnol.security.adjuster-token", "a-long-random-value-1")
                .withProperty("fnol.claims-events.secret", "a-long-random-value-2")
                .withProperty("fnol.portal.token-secret", "a-long-random-value-3")
                .withProperty("spring.datasource.password", "a-long-random-value-4")
                .withProperty("gemini.api-key", "a-long-random-value-5");
        env.setActiveProfiles("guidewire-cloud-claims", "guidewire-cloud-policy");
        return env;
    }

    @Test
    @DisplayName("A development stack is not checked")
    void developmentIsNotChecked() {
        assertThat(StartupConfigValidator.problems(new MockEnvironment())).isEmpty();
    }

    @Test
    @DisplayName("Production with real settings passes")
    void goodProduction() {
        assertThat(StartupConfigValidator.problems(production())).isEmpty();
    }

    @Test
    @DisplayName("A missing secret stops production, named but never shown")
    void missingSecret() {
        assertThat(StartupConfigValidator.problems(production().withProperty("fnol.portal.token-secret", " ")))
                .containsExactly("fnol.portal.token-secret is not set");
    }

    @Test
    @DisplayName("A demo value stops production, and the message does not repeat it")
    void demoValue() {
        assertThat(StartupConfigValidator.problems(production().withProperty("fnol.security.adjuster-token", "demo-adjuster-token")))
                .containsExactly("fnol.security.adjuster-token is a demo value");
    }

    @Test
    @DisplayName("A demo password in the account list stops production")
    void demoPassword() {
        assertThat(StartupConfigValidator.problems(production().withProperty("fnol.security.users", "ADJ-1|ADJUSTER|demo-password|A Person")))
                .containsExactly("fnol.security.users holds a demo password");
    }

    @Test
    @DisplayName("Weak password hashing stops production")
    void weakHashing() {
        assertThat(StartupConfigValidator.problems(production().withProperty("fnol.security.password-iterations", "1000"))).hasSize(1);
    }

    @Test
    @DisplayName("The mock claims or policy system stops production")
    void mockSystems() {
        MockEnvironment env = production();
        env.setActiveProfiles();
        assertThat(StartupConfigValidator.problems(env)).hasSize(2).anyMatch(p -> p.contains("claims system is the mock")).anyMatch(p -> p.contains("policy system is the mock"));
    }

    @Test
    @DisplayName("Gemini on with no key stops production; Gemini off does not need one")
    void geminiKey() {
        assertThat(StartupConfigValidator.problems(production().withProperty("gemini.api-key", ""))).hasSize(1);
        assertThat(StartupConfigValidator.problems(production().withProperty("gemini.api-key", "").withProperty("gemini.vision.enabled", "false"))).isEmpty();
    }
}
