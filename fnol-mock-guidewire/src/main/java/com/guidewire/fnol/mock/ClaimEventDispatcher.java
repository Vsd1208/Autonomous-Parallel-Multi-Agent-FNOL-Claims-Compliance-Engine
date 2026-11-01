package com.guidewire.fnol.mock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

/**
 * Delivers the claims system's events to its subscribers (the AI extension), like an event-publishing integration
 * (modelled on the idea of ClaimCenter App Events; names and headers are this project's, not Guidewire's).
 *
 * <p>Every second it posts each due event to every subscriber, signed with HMAC-SHA256 over the exact body
 * ({@code X-Claims-Signature: sha256=<hex>}) and identified by {@code X-Claims-Event-Id}. A 2xx from every subscriber
 * marks it delivered; otherwise it is retried with exponential backoff (at-least-once delivery). Without a subscriber
 * or a secret nothing is sent: an unsigned event would be refused anyway.
 */
@Component
class ClaimEventDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ClaimEventDispatcher.class);
    static final String SIGNATURE_HEADER = "X-Claims-Signature";
    static final String EVENT_ID_HEADER = "X-Claims-Event-Id";

    private final ClaimEventOutbox outbox;
    private final List<String> subscribers;
    private final byte[] secret;
    private final RestTemplate rest;

    ClaimEventDispatcher(ClaimEventOutbox outbox,
                         @Value("${claims.events.subscribers:}") String subscribers,
                         @Value("${claims.events.secret:}") String secret) {
        this.outbox = outbox;
        this.subscribers = Arrays.stream(subscribers.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(10000);
        this.rest = new RestTemplate(factory);
        if (this.subscribers.isEmpty() || this.secret.length == 0) {
            log.warn("[Claims events] No subscriber or no signing secret configured: events are stored but not sent");
        } else {
            log.info("[Claims events] Delivering to {}", this.subscribers);
        }
    }

    @Scheduled(fixedDelayString = "${claims.events.poll-ms:1000}")
    void deliver() {
        if (subscribers.isEmpty() || secret.length == 0) {
            return;
        }
        for (ClaimEventOutbox.Pending e : outbox.due(20)) {
            String error = null;
            for (String url : subscribers) {
                try {
                    HttpHeaders headers = new HttpHeaders();
                    headers.setContentType(MediaType.APPLICATION_JSON);
                    headers.set(EVENT_ID_HEADER, e.eventId());
                    headers.set(SIGNATURE_HEADER, "sha256=" + sign(secret, e.payload()));
                    rest.postForEntity(url, new HttpEntity<>(e.payload(), headers), String.class);
                } catch (RuntimeException ex) {
                    error = url + ": " + ex.getMessage();
                }
            }
            if (error == null) {
                outbox.delivered(e.eventId());
            } else {
                outbox.failed(e.eventId(), e.attempts(), error);
                log.warn("[Claims events] Event {} not delivered (attempt {}): {}", e.eventId(), e.attempts() + 1, error);
            }
        }
    }

    static String sign(byte[] secret, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot sign the event", e);
        }
    }
}
