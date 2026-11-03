package com.guidewire.fnol.api;

import com.guidewire.fnol.agents.DraftingAgent;
import com.guidewire.fnol.agents.GeminiDraftingModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** With the switch at its default nothing of the drafting agent exists: no beans, no endpoints, no call to any model. */
@SpringBootTest
@AutoConfigureMockMvc
class DraftingSwitchOffTest {

    @Autowired private ApplicationContext context;
    @Autowired private MockMvc mockMvc;

    @Test
    @DisplayName("Off by default: no agent, model, service or controller, and the endpoints do not exist")
    void offByDefault() throws Exception {
        assertThat(context.getBeansOfType(DraftingAgent.class)).isEmpty();
        assertThat(context.getBeansOfType(GeminiDraftingModel.class)).isEmpty();
        assertThat(context.getBeansOfType(ClaimDraftingService.class)).isEmpty();
        assertThat(context.getBeansOfType(ClaimDraftController.class)).isEmpty();
        // No route answers: this application's catch-all reports any unknown path as an error, as it does for every other one.
        assertThat(mockMvc.perform(get("/api/v1/fnol/CLM-2026-000001/drafts").header("X-Adjuster-Token", "test-adjuster-token"))
                .andReturn().getResponse().getStatus()).isGreaterThanOrEqualTo(400);
        assertThat(mockMvc.perform(post("/api/v1/fnol/CLM-2026-000001/drafts").header("X-Adjuster-Token", "test-adjuster-token")
                .contentType("application/json").content("{\"kind\":\"ADVISORY_NOTE\"}")).andReturn().getResponse().getStatus()).isGreaterThanOrEqualTo(400);
    }
}
