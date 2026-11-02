package com.guidewire.fnol.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Sets a claim's segment from its line of business, severity, flags and reserve, using rules kept as data
 * ({@code segmentation-rules.json}, first match wins). Rule-based on purpose: who handles a claim must be explainable. Modelled
 * on ClaimCenter's idea of segmenting a claim, not verified against a tenant.
 */
@Component
class ClaimSegmenter {

    static final String RESOURCE = "segmentation-rules.json";

    record Input(String line, String severity, Set<String> flags, BigDecimal reserve) {}

    record Segment(String segment, String why, String group, List<String> roles) {}

    private final JsonNode rules;

    ClaimSegmenter() {
        this(read());
    }

    ClaimSegmenter(JsonNode rules) {
        this.rules = rules;
    }

    private static JsonNode read() {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            return new ObjectMapper().readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + RESOURCE, e);
        }
    }

    Segment segment(Input claim) {
        for (JsonNode rule : rules.path("rules")) {
            if (matches(rule, claim)) {
                return build(rule);
            }
        }
        return build(rules.path("fallback"));
    }

    private Segment build(JsonNode rule) {
        String segment = rule.path("segment").asText();
        JsonNode group = rules.path("groups").path(segment);
        List<String> roles = new ArrayList<>();
        group.path("roles").forEach(r -> roles.add(r.asText()));
        return new Segment(segment, rule.path("why").asText(), group.path("group").asText(segment), roles);
    }

    private static boolean matches(JsonNode rule, Input claim) {
        if (rule.has("line") && !rule.get("line").asText().equals(claim.line())) return false;
        if (rule.has("severity") && !contains(rule.get("severity"), claim.severity())) return false;
        if (rule.has("anyFlag")) {
            boolean any = false;
            for (JsonNode flag : rule.get("anyFlag")) any |= claim.flags().contains(flag.asText());
            if (!any) return false;
        }
        if (rule.path("noFlags").asBoolean(false) && !claim.flags().isEmpty()) return false;
        BigDecimal reserve = claim.reserve() == null ? BigDecimal.ZERO : claim.reserve();
        if (rule.has("reserveOver") && reserve.compareTo(rule.get("reserveOver").decimalValue()) <= 0) return false;
        return !rule.has("reserveAtMost") || reserve.compareTo(rule.get("reserveAtMost").decimalValue()) <= 0;
    }

    private static boolean contains(JsonNode values, String value) {
        for (JsonNode v : values) {
            if (v.asText().equals(value)) return true;
        }
        return false;
    }
}
