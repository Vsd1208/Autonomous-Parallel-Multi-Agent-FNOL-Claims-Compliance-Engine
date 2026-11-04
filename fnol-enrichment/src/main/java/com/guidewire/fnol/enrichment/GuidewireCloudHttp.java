package com.guidewire.fnol.enrichment;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.UUID;

/**
 * One authenticated Cloud API call. A rejected token is replaced and the call tried once more; a 429 is waited out once, when the wait is
 * short. A server error or a broken connection is not retried here, since a write might have happened: the event inbox decides. The
 * {@code data.attributes} envelope is an assumption until a sandbox confirms it.
 */
final class GuidewireCloudHttp {

    private static final Logger log = LoggerFactory.getLogger(GuidewireCloudHttp.class);
    /** Carries this claim's trace to the tenant's logs. Whether a tenant records this header is to be checked in a sandbox. */
    static final String CORRELATION_HEADER = "X-Correlation-ID";
    static final String CORRELATION_MDC_KEY = "correlationId";
    /** Longest wait for a rate limit: a caller is holding a claim's processing budget. */
    static final long MAX_RATE_LIMIT_WAIT_MILLIS = 2_000;

    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private final RestTemplate rest;
    private final String baseUrl;
    private final GuidewireTokenProvider tokens;
    private final Sleeper sleeper;

    GuidewireCloudHttp(RestTemplate rest, String baseUrl, GuidewireTokenProvider tokens, Sleeper sleeper) {
        this.rest = rest;
        this.baseUrl = baseUrl;
        this.tokens = tokens;
        this.sleeper = sleeper;
    }

    /** With attributes, sent in the data.attributes envelope. */
    JsonNode call(HttpMethod method, String path, Map<String, Object> attributes, Object... uriVariables) {
        Object body = attributes == null ? null : Map.of("data", Map.of("attributes", attributes));
        String correlationId = MDC.get(CORRELATION_MDC_KEY) != null ? MDC.get(CORRELATION_MDC_KEY) : UUID.randomUUID().toString();
        boolean tokenRetried = false;
        boolean rateRetried = false;
        while (true) {
            String token = tokens.token();
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(token);
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set(CORRELATION_HEADER, correlationId);
            try {
                return rest.exchange(baseUrl + path, method, new HttpEntity<>(body, headers), JsonNode.class, uriVariables).getBody();
            } catch (HttpClientErrorException.Unauthorized e) {
                tokens.rejected(token);
                if (tokenRetried) {
                    log.warn("[Guidewire] {} {} rejected a new token too, correlationId={}", method, path, correlationId);
                    throw new GuidewireUnavailableException(GuidewireUnavailableException.Reason.AUTHENTICATION, "The tenant rejected a newly issued token");
                }
                tokenRetried = true;
            } catch (HttpClientErrorException.TooManyRequests e) {
                long wait = retryAfterMillis(e.getResponseHeaders());
                if (rateRetried || wait < 0 || wait > MAX_RATE_LIMIT_WAIT_MILLIS) {
                    log.warn("[Guidewire] {} {} is rate limited, correlationId={}", method, path, correlationId);
                    throw new GuidewireUnavailableException(GuidewireUnavailableException.Reason.RATE_LIMITED, "The tenant is rate limiting this client");
                }
                rateRetried = true;
                pause(wait);
            } catch (HttpServerErrorException e) {
                log.warn("[Guidewire] {} {} answered HTTP {}, correlationId={}", method, path, e.getStatusCode().value(), correlationId);
                throw new GuidewireUnavailableException(GuidewireUnavailableException.Reason.SERVER_ERROR, "The tenant answered HTTP " + e.getStatusCode().value());
            } catch (ResourceAccessException e) {
                log.warn("[Guidewire] {} {} could not be reached, correlationId={}", method, path, correlationId);
                throw new GuidewireUnavailableException(GuidewireUnavailableException.Reason.UNREACHABLE, "The tenant could not be reached");
            }
        }
    }

    /** Retry-After in seconds. -1 when absent or not a number of seconds (the date form is not handled: treated as too long to wait). */
    private static long retryAfterMillis(HttpHeaders headers) {
        String value = headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (value == null) {
            return -1;
        }
        try {
            return Long.parseLong(value.trim()) * 1000;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void pause(long millis) {
        try {
            sleeper.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GuidewireUnavailableException(GuidewireUnavailableException.Reason.RATE_LIMITED, "Interrupted while waiting out a rate limit");
        }
    }
}
