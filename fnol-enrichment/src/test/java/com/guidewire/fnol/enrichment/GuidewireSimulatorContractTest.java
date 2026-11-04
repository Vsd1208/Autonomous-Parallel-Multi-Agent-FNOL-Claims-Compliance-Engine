package com.guidewire.fnol.enrichment;

import com.fasterxml.jackson.databind.JsonNode;
import com.guidewire.fnol.common.Models.ClaimHistory;
import com.guidewire.fnol.common.Models.Coverage;
import com.guidewire.fnol.common.Models.FNOLPayload;
import com.guidewire.fnol.common.Models.Policy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * SIMULATOR, NOT A TENANT. Every response here is written by this project from its reading of the public documentation and its own
 * assumptions, so a pass shows the clients handle these answers and failures as intended. It is no evidence that a real tenant answers this way.
 */
@ExtendWith(OutputCaptureExtension.class)
class GuidewireSimulatorContractTest {

    private static final String BASE = "https://tenant.simulator/api";
    private static final String TOKEN_URL = "https://auth.simulator/token";
    private static final String SECRET = "SECRET-VALUE-NEVER-SHOWN";
    private static final String TOKEN_1 = "TOKEN-ONE-NEVER-SHOWN";
    private static final String TOKEN_2 = "TOKEN-TWO-NEVER-SHOWN";

    private final RestTemplate rest = new RestTemplate();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(rest).ignoreExpectOrder(false).build();
    private final List<Long> sleeps = new ArrayList<>();
    private final MutableClock clock = new MutableClock();
    private final GuidewireTokenProvider tokens = new GuidewireTokenProvider(rest, TOKEN_URL, "client", SECRET, clock);
    private final GuidewireCloudHttp http = new GuidewireCloudHttp(rest, BASE, tokens, sleeps::add);
    private final GuidewireCloudPolicyCenterClient policies = new GuidewireCloudPolicyCenterClient(rest, BASE, TOKEN_URL, "client", SECRET,
            "/simulated/coverages/{policyNumber}", "PersonalAuto:AUTO,Homeowners:HOME");

    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-04T10:00:00Z");

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    private void issues(String token, long expiresIn) {
        server.expect(requestTo(TOKEN_URL)).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"access_token\":\"" + token + "\",\"expires_in\":" + expiresIn + "}", MediaType.APPLICATION_JSON));
    }

    private JsonNode get() {
        return http.call(HttpMethod.GET, "/claim/v1/claims/1", null);
    }

    private static HttpHeaders retryAfter(String seconds) {
        HttpHeaders h = new HttpHeaders();
        h.set(HttpHeaders.RETRY_AFTER, seconds);
        return h;
    }

    private org.springframework.test.web.client.ResponseActions claimCall() {
        return server.expect(requestTo(BASE + "/claim/v1/claims/1"));
    }

    // ── Authentication ──

    @Test
    @DisplayName("A token is reused until shortly before it expires, then replaced")
    void tokenCachedThenRenewed() {
        issues(TOKEN_1, 3600);
        claimCall().andExpect(header("Authorization", "Bearer " + TOKEN_1)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        claimCall().andExpect(header("Authorization", "Bearer " + TOKEN_1)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        issues(TOKEN_2, 3600);
        claimCall().andExpect(header("Authorization", "Bearer " + TOKEN_2)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        get();
        clock.now = clock.now.plus(Duration.ofSeconds(3569));
        get();
        clock.now = clock.now.plus(Duration.ofSeconds(1));   // 30 s before the real expiry
        get();
        server.verify();
    }

    @Test
    @DisplayName("A token the tenant rejects is replaced and the call succeeds on the one retry")
    void rejectedTokenReacquired() {
        issues(TOKEN_1, 3600);
        claimCall().andExpect(header("Authorization", "Bearer " + TOKEN_1)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        issues(TOKEN_2, 3600);
        claimCall().andExpect(header("Authorization", "Bearer " + TOKEN_2)).andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        assertThat(get().path("ok").asBoolean()).isTrue();
        server.verify();
    }

    @Test
    @DisplayName("Rejected twice: stops, a transient failure, and does not loop")
    void rejectedTwiceStops() {
        issues(TOKEN_1, 3600);
        claimCall().andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        issues(TOKEN_2, 3600);
        claimCall().andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(this::get).isInstanceOfSatisfying(GuidewireUnavailableException.class,
                e -> assertThat(e.reason()).isEqualTo(GuidewireUnavailableException.Reason.AUTHENTICATION));
        server.verify();
    }

    @Test
    @DisplayName("Token endpoint failing: a clear failure, with neither the secret nor its answer in the message or the log")
    void tokenEndpointFailure(CapturedOutput output) {
        server.expect(requestTo(TOKEN_URL)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).body("detail " + SECRET));

        assertThatThrownBy(this::get).isInstanceOfSatisfying(GuidewireUnavailableException.class, e -> {
            assertThat(e.reason()).isEqualTo(GuidewireUnavailableException.Reason.AUTHENTICATION);
            assertThat(e.getMessage()).contains("500").doesNotContain(SECRET);
            assertThat(e.getCause()).isNull();
        });
        assertThat(output.getAll()).doesNotContain(SECRET);
    }

    @Test
    @DisplayName("An answer with no access token is a failure, not a call with an empty token")
    void noAccessToken() {
        server.expect(requestTo(TOKEN_URL)).andRespond(withSuccess("{\"token_type\":\"bearer\"}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(this::get).isInstanceOf(GuidewireUnavailableException.class).hasMessageContaining("access_token");
        server.verify();
    }

    @Test
    @DisplayName("Tokens never reach the log, even when calls fail")
    void tokensNotLogged(CapturedOutput output) {
        issues(TOKEN_1, 3600);
        claimCall().andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).body(TOKEN_1));
        assertThatThrownBy(this::get).isInstanceOf(GuidewireUnavailableException.class).satisfies(e -> assertThat(e.getMessage()).doesNotContain(TOKEN_1));
        assertThat(output.getAll()).doesNotContain(TOKEN_1).doesNotContain(SECRET).contains("HTTP 503");
    }

    @Test
    @DisplayName("Missing settings stop startup, naming the settings and not showing the ones that are set")
    void missingConfigurationFailsFast() {
        assertThatThrownBy(() -> new GuidewireCloudPolicyCenterClient(rest, "", TOKEN_URL, "client", SECRET, "", ""))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("guidewire.cloud.base-url").hasMessageNotContaining(SECRET);
        assertThatThrownBy(() -> new GuidewireCloudClaimCenterClient(rest, BASE, TOKEN_URL, "client", " "))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("guidewire.cloud.client-secret");
    }

    // ── Rate limits, outages and traces ──

    @Test
    @DisplayName("A short rate limit is waited out once, for as long as the tenant asked")
    void rateLimitWaitedOut() {
        issues(TOKEN_1, 3600);
        claimCall().andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).headers(retryAfter("1")));
        claimCall().andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        assertThat(get().path("ok").asBoolean()).isTrue();
        assertThat(sleeps).containsExactly(1000L);
        server.verify();
    }

    @Test
    @DisplayName("A second rate limit stops; so does a long or missing Retry-After, without waiting")
    void rateLimitGivesUp() {
        issues(TOKEN_1, 3600);
        claimCall().andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).headers(retryAfter("1")));
        claimCall().andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).headers(retryAfter("1")));
        claimCall().andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).headers(retryAfter("600")));
        claimCall().andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(this::get).isInstanceOfSatisfying(GuidewireUnavailableException.class,
                    e -> assertThat(e.reason()).isEqualTo(GuidewireUnavailableException.Reason.RATE_LIMITED));
        }
        assertThat(sleeps).containsExactly(1000L);
        server.verify();
    }

    @Test
    @DisplayName("A server error on a write is not retried here, since the write may have happened: exactly one call")
    void serverErrorNotRetried() {
        issues(TOKEN_1, 3600);
        server.expect(ExpectedCount.once(), requestTo(BASE + "/claim/v1/claims/1/notes")).andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThatThrownBy(() -> http.call(HttpMethod.POST, "/claim/v1/claims/1/notes", Map.of("body", "x")))
                .isInstanceOfSatisfying(GuidewireUnavailableException.class, e -> assertThat(e.reason()).isEqualTo(GuidewireUnavailableException.Reason.SERVER_ERROR));
        server.verify();
    }

    @Test
    @DisplayName("Failures are ones the event inbox retries")
    void failuresAreTransient() {
        assertThat(new GuidewireUnavailableException(GuidewireUnavailableException.Reason.UNREACHABLE, "x"))
                .isInstanceOf(org.springframework.web.client.ResourceAccessException.class);
    }

    @Test
    @DisplayName("Other client errors reach the caller unchanged, so a 404 or a duplicate's 409 keep their meaning")
    void clientErrorsPassThrough() {
        issues(TOKEN_1, 3600);
        claimCall().andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertThatThrownBy(this::get).isInstanceOf(HttpClientErrorException.NotFound.class);
    }

    @Test
    @DisplayName("The claim's correlation ID goes with the call, and a call without one still gets one")
    void correlationId() {
        MDC.put(GuidewireCloudHttp.CORRELATION_MDC_KEY, "corr-123");
        issues(TOKEN_1, 3600);
        claimCall().andExpect(header(GuidewireCloudHttp.CORRELATION_HEADER, "corr-123")).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        claimCall().andExpect(request -> assertThat(request.getHeaders().getFirst(GuidewireCloudHttp.CORRELATION_HEADER)).isNotBlank().isNotEqualTo("corr-123"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        get();
        MDC.clear();
        get();
        server.verify();
    }

    // ── PolicyCenter mapping ──

    private void policySearch(String answer) {
        server.expect(requestTo(BASE + "/policy/v1/search/policies")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.data.attributes.policyNumber").value("P-1"))
                .andRespond(withSuccess(answer, MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("A policy maps status, dates, line, cancellation and endorsements onto the domain's types")
    void policyMapping() {
        issues(TOKEN_1, 3600);
        policySearch("""
                {"data":[{"attributes":{"policyNumber":"P-1","product":{"code":"PersonalAuto"},"status":{"code":"InForce"},
                 "effectiveDate":"2026-01-01T00:00:00.000Z","expirationDate":"2027-01-01","cancellationDate":"2026-09-01",
                 "insuredName":"Simulated Insured","jurisdiction":{"code":"CA"},"currency":"usd",
                 "endorsements":[{"effectiveDate":"2026-06-01","change":"ADD","coverageType":"COLLISION","limit":{"amount":"5000.00"},"deductible":500}]}}]}""");

        Policy p = policies.getPolicy("P-1");

        assertThat(p.policyType()).isEqualTo("AUTO");
        assertThat(p.status()).isEqualTo("InForce");
        assertThat(p.effectiveDate()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(p.expirationDate()).isEqualTo(LocalDate.of(2027, 1, 1));
        assertThat(p.cancellationDate()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(p.state()).isEqualTo("CA");
        assertThat(p.endorsements()).singleElement().satisfies(e -> {
            assertThat(e.coverageType()).isEqualTo("COLLISION");
            assertThat(e.effectiveDate()).isEqualTo(LocalDate.of(2026, 6, 1));
        });
    }

    @Test
    @DisplayName("Version tolerance: unknown fields are ignored, and optional ones may be missing")
    void toleratesUnknownAndMissingFields() {
        issues(TOKEN_1, 3600);
        policySearch("""
                {"count":1,"links":{},"data":[{"newEnvelopeField":1,"attributes":{"policyNumber":"P-1","aFieldFromALaterRelease":{"x":[1,2]},
                 "effectiveDate":"2026-01-01"}}]}""");

        Policy p = policies.getPolicy("P-1");

        assertThat(p.policyNumber()).isEqualTo("P-1");
        assertThat(p.cancellationDate()).isNull();
        assertThat(p.endorsements()).isEmpty();
        assertThat(p.policyType()).as("an unmapped product is unknown, not guessed").isNull();
    }

    @Test
    @DisplayName("No match is 'not found', as with the mock's 404; two matches are an error, with no guess between them")
    void notFoundAndAmbiguous() {
        issues(TOKEN_1, 3600);
        policySearch("{\"data\":[]}");
        policySearch("{\"data\":[{\"attributes\":{}},{\"attributes\":{}}]}");
        assertThat(policies.getPolicy("P-1")).isNull();
        assertThatThrownBy(() -> policies.getPolicy("P-1")).isInstanceOf(IllegalStateException.class).hasMessageContaining("2 policies");
    }

    @Test
    @DisplayName("A malformed date fails: it must not read as a policy with no date")
    void malformedDateFails() {
        issues(TOKEN_1, 3600);
        policySearch("{\"data\":[{\"attributes\":{\"effectiveDate\":\"soon\"}}]}");
        assertThatThrownBy(() -> policies.getPolicy("P-1")).isInstanceOf(java.time.format.DateTimeParseException.class);
    }

    @Test
    @DisplayName("Coverages use only a configured path, and without one the client refuses and does not answer 'no coverage'")
    void coverages() {
        issues(TOKEN_1, 3600);
        server.expect(requestTo(BASE + "/simulated/coverages/P-1")).andRespond(withSuccess("""
                {"data":[{"attributes":{"coverageType":{"code":"COLLISION"},"limit":{"amount":25000},"deductible":{"amount":500}}}]}""", MediaType.APPLICATION_JSON));
        List<Coverage> found = policies.getCoverages("P-1");
        assertThat(found).singleElement().satisfies(c -> {
            assertThat(c.type()).isEqualTo("COLLISION");
            assertThat(c.limit()).isEqualByComparingTo(new BigDecimal("25000"));
            assertThat(c.deductible()).isEqualByComparingTo(new BigDecimal("500"));
        });

        GuidewireCloudPolicyCenterClient noPath = new GuidewireCloudPolicyCenterClient(rest, BASE, TOKEN_URL, "client", SECRET, "", "");
        assertThatThrownBy(() -> noPath.getCoverages("P-1")).isInstanceOf(UnsupportedOperationException.class).hasMessageContaining("guidewire.cloud.coverages-path");
    }

    @Test
    @DisplayName("Claim history comes newest first, and a claim with no loss date fails: frequency must not be undercounted quietly")
    void history() {
        issues(TOKEN_1, 3600);
        server.expect(requestTo(BASE + "/claim/v1/search/claims-v2")).andExpect(jsonPath("$.data.attributes.policyNumber").value("P-1")).andRespond(withSuccess("""
                {"data":[{"id":"c1","attributes":{"lossDate":"2025-01-01T00:00:00.000Z","state":{"code":"closed"},"totalPaid":{"amount":100}}},
                         {"id":"c2","attributes":{"lossDate":"2026-01-01","state":{"code":"open"}}}]}""", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/claim/v1/search/claims-v2")).andRespond(withSuccess("{\"data\":[{\"id\":\"c3\",\"attributes\":{}}]}", MediaType.APPLICATION_JSON));
        assertThat(policies.getPolicyClaimHistory("P-1")).extracting(ClaimHistory::claimId).containsExactly("c2", "c1");
        assertThatThrownBy(() -> policies.getPolicyClaimHistory("P-1")).isInstanceOf(IllegalStateException.class).hasMessageContaining("no loss date");
    }

    // ── ClaimCenter through the same transport ──

    @Test
    @DisplayName("ClaimCenter: a duplicate loss still maps to its own exception, and an outage on submit is a transient failure")
    void claimCenterThroughTheTransport() {
        GuidewireCloudClaimCenterClient claims = new GuidewireCloudClaimCenterClient(rest, BASE, TOKEN_URL, "client", SECRET);
        FNOLPayload fnol = new FNOLPayload("P-1", LocalDate.of(2026, 10, 20), "CA", "loss", "Simulated Claimant", null, null, List.of(), "WEB");
        issues(TOKEN_1, 3600);
        server.expect(requestTo(BASE + "/claim/v1/claims")).andRespond(withStatus(HttpStatus.CONFLICT));
        server.expect(requestTo(BASE + "/claim/v1/claims")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertThatThrownBy(() -> claims.submitFnol(fnol)).isInstanceOf(DuplicateLossException.class);
        assertThatThrownBy(() -> claims.submitFnol(fnol)).isInstanceOf(GuidewireUnavailableException.class);
    }
}
