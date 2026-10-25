package com.guidewire.fnol.agents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Replaces Gemini's self-reported confidence with the share of answers that were actually correct,
 * measured on a labelled dataset by {@code tools/vision-eval/calibrate.py} (classpath
 * {@code vision-calibration.json}).
 *
 * <p>The map's {@code signal} says what it is keyed on:
 * <ul>
 *   <li>{@code probabilityMargin} — the gap between Gemini's two highest damage-type probabilities.
 *       Gemini's self-reported confidence is nearly constant and the same for right and wrong answers;
 *       the margin separates specific answers from hedged ones (e.g. MULTIPLE).</li>
 *   <li>{@code confidence} (default) — Gemini's self-reported confidence.</li>
 * </ul>
 *
 * <p>The map is only valid for the model and thinking budget it was measured with, so it is applied
 * only when both match the configuration; otherwise confidence passes through unchanged and a warning
 * is logged. Disable with {@code gemini.vision.calibration.enabled=false}.
 */
@Component
public class VisionConfidenceCalibrator {

    private static final Logger log = LoggerFactory.getLogger(VisionConfidenceCalibrator.class);
    static final String RESOURCE = "vision-calibration.json";

    static final String SIGNAL_MARGIN = "probabilityMargin";

    record Step(BigDecimal from, BigDecimal calibrated) {}

    private final List<Step> steps;
    private final boolean marginSignal;

    @Autowired
    public VisionConfidenceCalibrator(
            @Value("${gemini.vision.calibration.enabled:true}") boolean enabled,
            @Value("${gemini.vision.model:gemini-2.5-flash}") String model,
            @Value("${gemini.vision.thinking-budget:0}") int thinkingBudget) {
        this(enabled ? read(new ClassPathResource(RESOURCE)) : null, model, thinkingBudget);
    }

    VisionConfidenceCalibrator(JsonNode calibration, String model, int thinkingBudget) {
        this.steps = load(calibration, model, thinkingBudget);
        this.marginSignal = calibration != null && SIGNAL_MARGIN.equals(calibration.path("signal").asText());
    }

    /** A calibrator that leaves confidence unchanged. */
    public static VisionConfidenceCalibrator none() {
        return new VisionConfidenceCalibrator(null, null, 0);
    }

    public boolean isActive() {
        return !steps.isEmpty();
    }

    /**
     * Calibrated confidence for one Gemini answer (step function on the map's signal; a value below the
     * first step uses the first). Returns {@code raw} unchanged when no map is active.
     */
    public BigDecimal calibrate(BigDecimal raw, Map<String, BigDecimal> damageTypeProbabilities) {
        if (steps.isEmpty()) {
            return raw;
        }
        BigDecimal signal = marginSignal ? margin(damageTypeProbabilities) : raw;
        if (signal == null) {
            return raw;
        }
        BigDecimal result = steps.get(0).calibrated();
        for (Step step : steps) {
            if (signal.compareTo(step.from()) >= 0) {
                result = step.calibrated();
            }
        }
        return result;
    }

    /** Calibrated confidence for a confidence-keyed map. */
    public BigDecimal calibrate(BigDecimal raw) {
        return calibrate(raw, Map.of());
    }

    /**
     * Top damage-type probability minus the runner-up, rounded to 2 decimals; 0 when Gemini gave no
     * probabilities (a hedged answer). Must match {@code margin()} in tools/vision-eval/calibrate.py.
     */
    static BigDecimal margin(Map<String, BigDecimal> probabilities) {
        if (probabilities == null || probabilities.isEmpty()) {
            return BigDecimal.ZERO;
        }
        List<BigDecimal> sorted = probabilities.values().stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.reverseOrder())
                .toList();
        if (sorted.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal second = sorted.size() > 1 ? sorted.get(1) : BigDecimal.ZERO;
        return sorted.get(0).subtract(second).setScale(2, RoundingMode.HALF_UP);
    }

    private static List<Step> load(JsonNode calibration, String model, int thinkingBudget) {
        if (calibration == null) {
            return List.of();
        }
        String forModel = calibration.path("model").asText();
        int forBudget = calibration.path("thinkingBudget").asInt(-1);
        if (!forModel.equals(model) || forBudget != thinkingBudget) {
            log.warn("[GeminiVision] Confidence calibration is for {} (thinking budget {}) but the configuration is {} "
                    + "(thinking budget {}); confidence will NOT be calibrated. Re-run tools/vision-eval.",
                    forModel, forBudget, model, thinkingBudget);
            return List.of();
        }
        List<Step> steps = new ArrayList<>();
        for (JsonNode block : calibration.path("table")) {
            steps.add(new Step(block.path("from").decimalValue(), block.path("calibrated").decimalValue()));
        }
        log.info("[GeminiVision] Confidence calibration active: {} steps from {} labelled images ({})",
                steps.size(), calibration.path("images").asInt(), calibration.path("dataset").asText());
        return List.copyOf(steps);
    }

    private static JsonNode read(ClassPathResource resource) {
        if (!resource.exists()) {
            log.info("[GeminiVision] No {} on the classpath; Gemini confidence is used as reported.", RESOURCE);
            return null;
        }
        try (InputStream in = resource.getInputStream()) {
            return new ObjectMapper().readTree(in);
        } catch (IOException e) {
            log.warn("[GeminiVision] Could not read {}; Gemini confidence is used as reported: {}", RESOURCE, e.getMessage());
            return null;
        }
    }
}
