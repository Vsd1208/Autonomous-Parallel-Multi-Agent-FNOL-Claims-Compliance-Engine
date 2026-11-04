package com.guidewire.fnol.enrichment;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * OAuth2 client credentials: reuses a token until shortly before it expires, and gets a new one when told the old one was rejected. The
 * request's shape is an assumption until a sandbox confirms it. Never logs a token, a secret or the token endpoint's answer. The secret
 * comes from configuration, and configuration missing at startup stops the application.
 */
final class GuidewireTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(GuidewireTokenProvider.class);
    /** Renew this long before expiry, so a token does not lapse in flight. */
    private static final long EXPIRY_MARGIN_SECONDS = 30;
    /** When the endpoint does not say how long a token lasts. */
    private static final long DEFAULT_LIFETIME_SECONDS = 300;

    private final RestTemplate rest;
    private final String tokenUrl;
    private final String clientId;
    private final String clientSecret;
    private final Clock clock;
    private String token;
    private Instant expires = Instant.EPOCH;

    GuidewireTokenProvider(RestTemplate rest, String tokenUrl, String clientId, String clientSecret, Clock clock) {
        this.rest = rest;
        this.tokenUrl = tokenUrl;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.clock = clock;
    }

    /** Stops startup when a setting is missing, naming the settings and never their values. */
    static void requireConfigured(String adapter, Map<String, String> settings) {
        List<String> missing = settings.entrySet().stream().filter(e -> e.getValue() == null || e.getValue().isBlank()).map(Map.Entry::getKey).sorted().toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException(adapter + " is enabled but not configured: set " + String.join(", ", missing));
        }
    }

    synchronized String token() {
        if (token != null && clock.instant().isBefore(expires)) {
            return token;
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.setBasicAuth(clientId, clientSecret);
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        JsonNode answer;
        try {
            answer = rest.postForObject(tokenUrl, new HttpEntity<>(form, headers), JsonNode.class);
        } catch (RestClientResponseException e) {
            log.warn("[Guidewire] The token endpoint refused the request: HTTP {}", e.getStatusCode().value());
            throw new GuidewireUnavailableException(GuidewireUnavailableException.Reason.AUTHENTICATION, "The token endpoint answered HTTP " + e.getStatusCode().value());
        } catch (RestClientException e) {
            log.warn("[Guidewire] The token endpoint could not be reached: {}", e.getClass().getSimpleName());
            throw new GuidewireUnavailableException(GuidewireUnavailableException.Reason.AUTHENTICATION, "The token endpoint could not be reached");
        }
        if (answer == null || !answer.hasNonNull("access_token")) {
            throw new GuidewireUnavailableException(GuidewireUnavailableException.Reason.AUTHENTICATION, "The token endpoint did not return an access_token");
        }
        token = answer.get("access_token").asText();
        expires = clock.instant().plusSeconds(Math.max(0, answer.path("expires_in").asLong(DEFAULT_LIFETIME_SECONDS) - EXPIRY_MARGIN_SECONDS));
        return token;
    }

    /** The tenant rejected this token: drop it, unless another thread already replaced it. */
    synchronized void rejected(String rejectedToken) {
        if (rejectedToken.equals(token)) {
            token = null;
        }
    }

    @Override
    public String toString() {
        return "GuidewireTokenProvider[" + tokenUrl + "]";
    }
}
