package com.guidewire.fnol.agents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Initial reserves from a versioned table ({@code initial-reserves.json}), each row with its source or labelled a synthetic
 * prototype value. Sources are looked up in {@code repair-cost-ranges.json}, so a figure and its citation live in one place.
 */
@Component
public class TableInitialReserveProvider implements InitialReserveProvider {

    static final String RESOURCE = "initial-reserves.json";
    static final String SOURCES = "repair-cost-ranges.json";

    private final JsonNode table;
    private final JsonNode sources;

    @Autowired
    public TableInitialReserveProvider() {
        this(read(RESOURCE), read(SOURCES).path("sources"));
    }

    public TableInitialReserveProvider(JsonNode table, JsonNode sources) {
        this.table = table;
        this.sources = sources;
    }

    private static JsonNode read(String name) {
        try (InputStream in = new ClassPathResource(name).getInputStream()) {
            return new ObjectMapper().readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + name, e);
        }
    }

    @Override
    public Optional<InitialReserve> lookup(String lineOfBusiness, String coverage, String severity) {
        if (lineOfBusiness == null || coverage == null || severity == null) {
            return Optional.empty();
        }
        String line = lineOfBusiness.toUpperCase(Locale.ROOT);
        String cover = coverage.toUpperCase(Locale.ROOT);
        String band = severity.toUpperCase(Locale.ROOT);
        JsonNode cell = table.path("reserves").path(line).path(cover).path(band);
        if (cell.isMissingNode() || !cell.path("amount").isNumber()) {
            return Optional.empty();
        }
        String provenance = cell.path("provenance").asText();
        return Optional.of(new InitialReserve(line + " / " + cover + " / " + band, cell.path("amount").decimalValue(),
                provenance, describe(cell, provenance)));
    }

    private String describe(JsonNode cell, String provenance) {
        if (!"PUBLISHED".equals(provenance)) {
            return table.path("synthetic").asText("synthetic prototype value");
        }
        List<String> titles = new ArrayList<>();
        for (JsonNode id : cell.path("sources")) {
            titles.add(sources.path(id.asText()).path("title").asText(id.asText()));
        }
        return cell.path("mapping").asText() + "; " + String.join("; ", titles);
    }
}
