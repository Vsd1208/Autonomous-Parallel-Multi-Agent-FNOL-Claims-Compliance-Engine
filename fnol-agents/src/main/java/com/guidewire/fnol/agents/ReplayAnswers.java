package com.guidewire.fnol.agents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The recorded model answers of demo-scenarios/replay (tools/vision-eval/export_replay.py), found by the SHA-256 of a submitted
 * photo. Only with {@code fnol.vision.provider=replay}, which refuses to start with Gemini enabled, so replay mode cannot reach
 * an external model. An answer that cannot be read just leaves its photo unknown: it then goes to a person.
 */
@Component
@ConditionalOnProperty(name = "fnol.vision.provider", havingValue = "replay")
public class ReplayAnswers {

    private static final Logger log = LoggerFactory.getLogger(ReplayAnswers.class);

    private final Map<String, JsonNode> bySha = new HashMap<>();
    private final JsonNode manifest;

    @Autowired
    public ReplayAnswers(@Value("${fnol.replay.dir:demo-scenarios/replay}") String dir,
                         @Value("${gemini.vision.enabled:true}") boolean geminiEnabled) {
        if (geminiEnabled) {
            throw new IllegalStateException("fnol.vision.provider=replay needs gemini.vision.enabled=false: replay mode must never reach an external model");
        }
        ObjectMapper mapper = new ObjectMapper();
        JsonNode loaded = mapper.createObjectNode();
        try {
            loaded = mapper.readTree(Files.readString(Path.of(dir, "manifest.json")));
            for (JsonNode a : mapper.readTree(Files.readString(Path.of(dir, "answers.json")))) {
                bySha.put(a.path("sha256").asText(), a);
            }
            log.info("[Replay] {} recorded answers loaded from {}", bySha.size(), dir);
        } catch (IOException e) {
            log.warn("[Replay] Recorded answers could not be read from {}; every photo will go to a person: {}", dir, e.getMessage());
        }
        this.manifest = loaded;
    }

    /** The recorded answer for the first submitted photo that has one. */
    public Optional<JsonNode> find(List<String> uris) {
        if (uris == null) {
            return Optional.empty();
        }
        for (String uri : uris) {
            JsonNode found = sha256OfData(uri).map(bySha::get).orElse(null);
            if (found != null) {
                return Optional.of(found);
            }
        }
        return Optional.empty();
    }

    public int size() {
        return bySha.size();
    }

    public JsonNode manifest() {
        return manifest;
    }

    /** For health and claim notes. */
    public String description() {
        return "replaying recorded model answers (" + manifest.path("recordedLabel").asText("date unknown") + ")";
    }

    private static Optional<String> sha256OfData(String uri) {
        if (uri == null || !uri.regionMatches(true, 0, "data:image/", 0, 11)) {
            return Optional.empty();
        }
        int comma = uri.indexOf(',');
        if (comma < 0) {
            return Optional.empty();
        }
        try {
            byte[] bytes = Base64.getMimeDecoder().decode(uri.substring(comma + 1));
            return Optional.of(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        } catch (IllegalArgumentException | NoSuchAlgorithmException e) {
            return Optional.empty();
        }
    }
}
