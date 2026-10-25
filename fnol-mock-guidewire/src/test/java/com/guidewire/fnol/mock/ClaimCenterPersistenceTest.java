package com.guidewire.fnol.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guidewire.fnol.common.Models.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ClaimCenterPersistenceTest {

    private final ObjectMapper mapper = Jackson2ObjectMapperBuilder.json().build();

    @Test
    @DisplayName("Claims, review actions and attachments are kept in the database across a restart; numbering continues")
    void claimsSurviveRestart() throws Exception {
        DriverManagerDataSource db = new DriverManagerDataSource(
                "jdbc:h2:mem:cc-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("claimcenter-schema.sql")).execute(db);

        ClaimCenterController first = new ClaimCenterController(new ClaimCenterStore(new JdbcTemplate(db), mapper), 1);
        String id = first.create(new ClaimCreateRequest("POL-AUTO-112233", LocalDate.of(2026, 8, 16), "CA",
                new BigDecimal("4200"), new BigDecimal("4830"), "DRAFT_PENDING_REVIEW")).claimId();
        first.attach(id, new ClaimAttachment("ai-assessment.json", "application/json", Map.of("severity", "LOW")));
        first.update(id, new ClaimUpdateRequest("PENDING_INFORMATION", "need photos", "NEEDS_MORE_INFO",
                "ADJ-001", null));

        // A new controller on the same database is what the service sees after a restart.
        ClaimCenterController restarted = new ClaimCenterController(new ClaimCenterStore(new JdbcTemplate(db), mapper), 1);

        Claim reloaded = restarted.get(id);
        assertThat(reloaded.status()).isEqualTo("PENDING_INFORMATION");
        assertThat(reloaded.notes()).isEqualTo("need photos");
        assertThat(reloaded.reviewActions()).extracting(ClaimReviewAction::action).containsExactly("NEEDS_MORE_INFO");
        assertThat(reloaded.attachments()).extracting(ClaimAttachment::name).containsExactly("ai-assessment.json");
        assertThat(reloaded.attachments().get(0).body()).isEqualTo(Map.of("severity", "LOW"));

        Claim approved = restarted.update(id, new ClaimUpdateRequest("OPEN", null, "APPROVED_BY_ADJUSTER", "ADJ-001",
                new BigDecimal("6100")));
        assertThat(approved.status()).isEqualTo("OPEN");
        assertThat(approved.reserve()).isEqualByComparingTo("6100");
        assertThat(approved.reviewActions()).hasSize(2);

        String next = restarted.create(new ClaimCreateRequest("POL-AUTO-112233", LocalDate.of(2026, 8, 17), "CA",
                new BigDecimal("100"), new BigDecimal("115"), "DRAFT_PENDING_REVIEW")).claimId();
        assertThat(next).isEqualTo("CLM-2026-000002");
    }
}
