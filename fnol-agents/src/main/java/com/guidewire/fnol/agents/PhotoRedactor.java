package com.guidewire.fnol.agents;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Blurs faces and licence plates in claim photos before they are sent to an external AI model, using the privacy
 * redaction of fnol-vision-ml ({@code fnol.redaction.url}). The originals stay in the insurer's own store.
 *
 * <p>With {@code fnol.redaction.required=true} (default) a photo is never sent unblurred: if the redaction service
 * fails, the caller must not call the external model (the claim then goes to an adjuster). With no URL configured,
 * redaction is off and photos are sent as they are (tests, local demos).
 */
@Component
public class PhotoRedactor {

    /** A redacted photo set, in the order given. */
    public record Redacted(List<String> imageDataUris, int faces, int plates) {}

    /** Thrown when photos could not be redacted and redaction is required. */
    public static class RedactionUnavailableException extends Exception {
        public RedactionUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final String url;
    private final boolean required;
    private final RestTemplate rest;

    @Autowired
    public PhotoRedactor(@Value("${fnol.redaction.url:}") String url,
                         @Value("${fnol.redaction.required:true}") boolean required,
                         @Value("${fnol.redaction.read-timeout-ms:6000}") long readTimeoutMs,
                         RestTemplateBuilder builder) {
        this(url, required, builder.setConnectTimeout(Duration.ofSeconds(2)).setReadTimeout(Duration.ofMillis(readTimeoutMs))
                // Buffered, so Content-Length is sent: the service does not accept chunked request bodies.
                .requestFactory(() -> new BufferingClientHttpRequestFactory(new SimpleClientHttpRequestFactory())).build());
    }

    protected PhotoRedactor(String url, boolean required, RestTemplate rest) {
        this.url = url == null ? "" : url.strip();
        this.required = required;
        this.rest = rest;
    }

    /** Redaction off: photos pass through unchanged. */
    public static PhotoRedactor disabled() {
        return new PhotoRedactor("", false, null);
    }

    public boolean isEnabled() {
        return !url.isEmpty();
    }

    /**
     * The photos with faces and plates blurred (as JPEG data URIs), or the originals when redaction is off or failed
     * and is not required.
     */
    public Redacted redact(List<String> imageDataUris, String lineOfBusiness) throws RedactionUnavailableException {
        if (!isEnabled() || imageDataUris.isEmpty()) {
            return new Redacted(imageDataUris, 0, 0);
        }
        try {
            String line = "HOME".equalsIgnoreCase(lineOfBusiness) ? "HOME" : "AUTO";
            JsonNode body = rest.postForObject(url + "/v1/redact", Map.of("line", line, "images", imageDataUris),
                    JsonNode.class);
            if (body == null || body.path("images").size() != imageDataUris.size()) {
                throw new IllegalStateException("unexpected redaction response");
            }
            List<String> images = new ArrayList<>();
            body.path("images").forEach(n -> images.add(n.asText()));
            int faces = 0, plates = 0;
            for (JsonNode n : body.path("faces")) faces += n.asInt();
            for (JsonNode n : body.path("plates")) plates += n.asInt();
            return new Redacted(images, faces, plates);
        } catch (RuntimeException e) {
            if (required) {
                throw new RedactionUnavailableException("Photos could not be redacted: " + e.getMessage(), e);
            }
            return new Redacted(imageDataUris, 0, 0);
        }
    }
}
