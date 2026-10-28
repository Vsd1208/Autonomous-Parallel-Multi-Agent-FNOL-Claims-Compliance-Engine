package com.guidewire.fnol.agents;

import com.fasterxml.jackson.databind.JsonNode;
import com.guidewire.fnol.agents.EvidenceImageLoader.EvidenceRejectedException;
import com.guidewire.fnol.agents.EvidenceImageLoader.ImageData;
import com.guidewire.fnol.common.Models.FNOLPayload;
import com.guidewire.fnol.common.Models.SecondOpinion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Branch A: asks the second-opinion vision model (fnol-vision-ml) about the same photos Gemini sees, loaded through
 * the same {@link EvidenceImageLoader} checks. Runs in parallel with Gemini. Best effort: when the service is off
 * ({@code fnol.second-opinion.url} empty), unreachable, slow or there is no usable photo, the result is null and the
 * claim is handled exactly as without it.
 */
@Component
public class SecondOpinionAgent {

    private static final Logger log = LoggerFactory.getLogger(SecondOpinionAgent.class);
    static final int MAX_PHOTOS = 8;

    private final String url;
    private final RestTemplate rest;
    private final EvidenceImageLoader evidence;
    private final ExecutorService executor;

    @Autowired
    public SecondOpinionAgent(@Value("${fnol.second-opinion.url:}") String url,
                              @Value("${fnol.second-opinion.read-timeout-ms:4000}") long readTimeoutMs,
                              RestTemplateBuilder builder,
                              EvidenceImageLoader evidence) {
        this(url, builder.setConnectTimeout(Duration.ofSeconds(2)).setReadTimeout(Duration.ofMillis(readTimeoutMs))
                // Buffered, so Content-Length is sent: the service does not accept chunked request bodies.
                .requestFactory(() -> new BufferingClientHttpRequestFactory(new SimpleClientHttpRequestFactory())).build(),
                evidence);
    }

    protected SecondOpinionAgent(String url, RestTemplate rest, EvidenceImageLoader evidence) {
        this.url = url == null ? "" : url.strip();
        this.rest = rest;
        this.evidence = evidence;
        this.executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "second-opinion");
            t.setDaemon(true);
            return t;
        });
    }

    /** An agent that never asks (tests, or deployments without the model). */
    public static SecondOpinionAgent disabled() {
        return new SecondOpinionAgent("", null, null);
    }

    public boolean isEnabled() {
        return !url.isEmpty();
    }

    /** Starts the request in the background; the future completes with null instead of failing. */
    public CompletableFuture<SecondOpinion> start(FNOLPayload payload, String lineOfBusiness) {
        if (!isEnabled()) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.supplyAsync(() -> ask(payload, lineOfBusiness), executor)
                .exceptionally(e -> {
                    log.warn("[SecondOpinion] Not available — claim continues without it: {}", e.getMessage());
                    return null;
                });
    }

    SecondOpinion ask(FNOLPayload payload, String lineOfBusiness) {
        List<String> images = new ArrayList<>();
        List<String> angles = new ArrayList<>();
        List<String> uris = payload.evidenceUris() == null ? List.of() : payload.evidenceUris();
        for (int i = 0; i < uris.size() && images.size() < MAX_PHOTOS; i++) {
            if (uris.get(i) == null || uris.get(i).isBlank()) continue;
            try {
                ImageData image = evidence.load(uris.get(i));
                images.add("data:" + image.mimeType() + ";base64," + image.base64());
                angles.add(payload.angleOf(i));
            } catch (EvidenceRejectedException e) {
                // Same rule as Gemini: refused evidence (mock://, http:, private hosts) is simply not sent.
            }
        }
        if (images.isEmpty()) {
            return null;
        }
        String line = "HOME".equalsIgnoreCase(lineOfBusiness) ? "HOME" : "AUTO";
        JsonNode body = rest.postForObject(url + "/v1/second-opinion",
                Map.of("line", line, "images", images, "angles", angles), JsonNode.class);
        if (body == null) {
            return null;
        }
        SecondOpinion result = new SecondOpinion(body.path("model").asText(),
                probabilities(body.path("damageProbabilities")), probabilities(body.path("subjectProbabilities")));
        log.info("[SecondOpinion] {} photo(s), line={}, damage={}", images.size(), line, result.damageProbabilities());
        return result;
    }

    private static Map<String, BigDecimal> probabilities(JsonNode node) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        node.fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue().decimalValue()));
        return out;
    }
}
