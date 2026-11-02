package com.guidewire.fnol.agents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guidewire.fnol.common.Models.RepairCostRange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * What repairing a damaged vehicle area usually costs, for the visible extent the on-premise model saw.
 *
 * <p>Two tables, in order of preference:
 * <ol>
 *   <li>{@value #LEARNED}: fitted on this insurer's own inspected and adjuster-confirmed repair costs by
 *       tools/cost-eval/fit_costs.py, which writes it only when it beats the published table out of sample on enough
 *       claims. Absent until then.</li>
 *   <li>{@value #PUBLISHED}: published US figures (AAA, Consumer Reports, CCC), each with its source. Which figure
 *       applies to each extent band is this project's mapping, an assumption.</li>
 * </ol>
 * Used to price claims the on-premise model read (Gemini unavailable) and to flag a Gemini estimate that is far
 * outside the range ({@link #farOutside}). It never pays or denies anything.
 */
@Component
public class RepairCostEstimator {

    private static final Logger log = LoggerFactory.getLogger(RepairCostEstimator.class);
    static final String PUBLISHED = "repair-cost-ranges.json";
    static final String LEARNED = "repair-cost-learned.json";
    /**
     * An estimate below low / TOLERANCE or above high x TOLERANCE is far outside. Replayed on 41 Gemini answers with a
     * published range (tools/cost-eval/check_gemini_estimates.py): 2x flags 12, 3x 6, 4x 3. Gemini's figures mostly restate
     * its severity band (tools/cost-eval/stock_figures.py: 86% fall on five values), so the flag errs towards a person looking.
     */
    public static final double TOLERANCE = 2.0;

    private final JsonNode published;
    private final JsonNode learned;

    public RepairCostEstimator() {
        this(read(PUBLISHED), read(LEARNED));
    }

    RepairCostEstimator(JsonNode published, JsonNode learned) {
        this.published = published;
        this.learned = learned;
        if (learned != null) {
            log.info("[RepairCost] Learned costs active ({} claims; {})", learned.path("claims").asInt(),
                    learned.path("metrics").toString());
        } else if (published != null) {
            log.info("[RepairCost] Published repair-cost ranges active (retrieved {})", published.path("retrieved").asText());
        }
    }

    /** No table (tests). */
    public static RepairCostEstimator none() {
        return new RepairCostEstimator(null, null);
    }

    private static JsonNode read(String name) {
        ClassPathResource resource = new ClassPathResource(name);
        if (!resource.exists()) return null;
        try (InputStream in = resource.getInputStream()) {
            return new ObjectMapper().readTree(in);
        } catch (IOException e) {
            log.warn("[RepairCost] {} could not be read: {}", name, e.getMessage());
            return null;
        }
    }

    /**
     * The range for a vehicle area and extent band (SMALL / MEDIUM / LARGE); with no band, the span of all bands with
     * the MEDIUM estimate. Null for areas without a range (MULTIPLE, UNKNOWN, ROOF, home causes of loss).
     */
    public RepairCostRange range(String area, String band) {
        if (area == null) return null;
        RepairCostRange fromLearned = learnedRange(area, band);
        if (fromLearned != null) return fromLearned;
        if (published == null) return null;
        JsonNode bands = published.path("ranges").path(area);
        if (bands.isMissingNode() || bands.isEmpty()) return null;
        JsonNode cell = band == null ? null : bands.get(band);
        if (cell != null) {
            double low = cell.path("low").asDouble(), high = cell.path("high").asDouble();
            return new RepairCostRange(area, band, money(low), money(high), money(geometricMean(low, high)), "PUBLISHED",
                    cell.path("mapping").asText(), sourceUrls(cell.path("sources")));
        }
        double low = Double.MAX_VALUE, high = 0;
        List<String> urls = new ArrayList<>();
        for (Iterator<JsonNode> it = bands.elements(); it.hasNext(); ) {
            JsonNode b = it.next();
            low = Math.min(low, b.path("low").asDouble());
            high = Math.max(high, b.path("high").asDouble());
            for (String u : sourceUrls(b.path("sources"))) if (!urls.contains(u)) urls.add(u);
        }
        JsonNode medium = bands.path("MEDIUM");
        double estimate = medium.isMissingNode() ? geometricMean(low, high)
                : geometricMean(medium.path("low").asDouble(), medium.path("high").asDouble());
        return new RepairCostRange(area, null, money(low), money(high), money(estimate), "PUBLISHED",
                "visible extent not measured: every band's range, with the MEDIUM estimate", urls);
    }

    private RepairCostRange learnedRange(String area, String band) {
        if (learned == null || band == null) return null;
        JsonNode cell = learned.path("cells").path(area).path(band);
        if (cell.isMissingNode()) return null;
        return new RepairCostRange(area, band, money(cell.path("low").asDouble()), money(cell.path("high").asDouble()),
                money(cell.path("estimate").asDouble()), "LEARNED",
                "fitted on " + cell.path("n").asInt() + " of this insurer's claims with an inspected or adjuster-confirmed "
                        + "repair cost (" + learned.path("claims").asInt() + " in total), shrunk toward the published range",
                List.of());
    }

    /** An estimate far outside the range: below low / TOLERANCE or above high x TOLERANCE. */
    public static boolean farOutside(BigDecimal estimate, RepairCostRange range) {
        if (estimate == null || range == null) return false;
        double e = estimate.doubleValue();
        return e < range.low().doubleValue() / TOLERANCE || e > range.high().doubleValue() * TOLERANCE;
    }

    private List<String> sourceUrls(JsonNode ids) {
        List<String> urls = new ArrayList<>();
        for (JsonNode id : ids) {
            String url = published.path("sources").path(id.asText()).path("url").asText("");
            if (!url.isEmpty()) urls.add(url);
        }
        return urls;
    }

    static double geometricMean(double low, double high) {
        return Math.sqrt(Math.max(low, 1) * Math.max(high, 1));
    }

    /** Rounded to $10. */
    private static BigDecimal money(double v) {
        return BigDecimal.valueOf(v).divide(BigDecimal.TEN, 0, RoundingMode.HALF_UP).multiply(BigDecimal.TEN);
    }
}
