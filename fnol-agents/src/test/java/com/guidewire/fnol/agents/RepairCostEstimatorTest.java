package com.guidewire.fnol.agents;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guidewire.fnol.common.Models.RepairCostRange;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class RepairCostEstimatorTest {

    private final RepairCostEstimator costs = new RepairCostEstimator();

    @Test
    @DisplayName("Published range for an area and extent band, with its sources; estimate = geometric mean, to $10")
    void publishedRange() {
        RepairCostRange r = costs.range("REAR_BUMPER", "MEDIUM");
        assertThat(r.basis()).isEqualTo("PUBLISHED");
        assertThat(r.low()).isEqualByComparingTo("1000");
        assertThat(r.high()).isEqualByComparingTo("1700");          // AAA's $1,698, rounded to $10
        assertThat(r.estimate()).isEqualByComparingTo("1300");      // sqrt(1000 x 1698) = 1303
        assertThat(r.sources()).anyMatch(u -> u.contains("newsroom.aaa.com"))
                .anyMatch(u -> u.contains("consumerreports.org"));
        assertThat(costs.range("WINDSHIELD", "SMALL").high()).isEqualByComparingTo("150");
    }

    @Test
    @DisplayName("Every published figure has a source with a URL and a quote")
    void everyRangeIsSourced() throws Exception {
        var json = new ObjectMapper().readTree(new ClassPathResource(RepairCostEstimator.PUBLISHED).getInputStream());
        json.path("ranges").forEach(bands -> bands.forEach(cell -> {
            assertThat(cell.path("low").asDouble()).isPositive().isLessThan(cell.path("high").asDouble());
            assertThat(cell.path("sources").size()).isPositive();
            cell.path("sources").forEach(id -> {
                var source = json.path("sources").path(id.asText());
                assertThat(source.path("url").asText()).startsWith("https://");
                assertThat(source.path("quote").asText()).contains("$");
            });
        }));
    }

    @Test
    @DisplayName("No extent band: the span of every band with the MEDIUM estimate; no range for MULTIPLE or home causes")
    void unknownBandAndUnknownArea() {
        RepairCostRange r = costs.range("FRONT_BUMPER", null);
        assertThat(r.extentBand()).isNull();
        assertThat(r.low()).isEqualByComparingTo("50");
        assertThat(r.high()).isEqualByComparingTo("11710");
        assertThat(r.estimate()).isEqualByComparingTo(costs.range("FRONT_BUMPER", "MEDIUM").estimate());
        assertThat(costs.range("MULTIPLE", "LARGE")).isNull();
        assertThat(costs.range("FLOOD", "LARGE")).isNull();
        assertThat(RepairCostEstimator.none().range("FRONT_BUMPER", "SMALL")).isNull();
    }

    @Test
    @DisplayName("Far outside = below low / 2 or above high x 2")
    void farOutside() {
        RepairCostRange small = costs.range("SIDE_PANEL", "SMALL");           // $50 - $200
        assertThat(RepairCostEstimator.farOutside(new BigDecimal("12000"), small)).isTrue();
        assertThat(RepairCostEstimator.farOutside(new BigDecimal("400"), small)).isFalse();
        assertThat(RepairCostEstimator.farOutside(new BigDecimal("401"), small)).isTrue();
        assertThat(RepairCostEstimator.farOutside(new BigDecimal("25"), small)).isFalse();   // 50 / 2
        assertThat(RepairCostEstimator.farOutside(new BigDecimal("24"), small)).isTrue();
        assertThat(RepairCostEstimator.farOutside(new BigDecimal("12"), null)).isFalse();
    }

    @Test
    @DisplayName("A learned cell (this insurer's own costs) replaces the published one; other cells stay published")
    void learnedOverridesPublished() throws Exception {
        var published = new ObjectMapper().readTree(new ClassPathResource(RepairCostEstimator.PUBLISHED).getInputStream());
        var learned = new ObjectMapper().readTree("""
                {"version": 1, "basis": "LEARNED", "claims": 64, "metrics": {},
                 "cells": {"FRONT_BUMPER": {"MEDIUM": {"low": 1200, "high": 3900, "estimate": 2160, "n": 18}}}}""");
        RepairCostEstimator withLearned = new RepairCostEstimator(published, learned);
        RepairCostRange r = withLearned.range("FRONT_BUMPER", "MEDIUM");
        assertThat(r.basis()).isEqualTo("LEARNED");
        assertThat(r.estimate()).isEqualByComparingTo("2160");
        assertThat(r.mapping()).contains("18").contains("64");
        assertThat(withLearned.range("FRONT_BUMPER", "LARGE").basis()).isEqualTo("PUBLISHED");
    }
}
