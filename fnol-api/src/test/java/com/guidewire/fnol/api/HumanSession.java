package com.guidewire.fnol.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.Map;

/** Signs a test account in, through the real login endpoint: a human decision needs a person, and tests are no exception. */
final class HumanSession {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HumanSession() {
    }

    /** The Authorization header value for an account in the test configuration, whose password is "p". */
    static String bearer(MockMvc mockMvc, String user) throws Exception {
        String body = mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of("username", user, "password", "p")))).andReturn().getResponse().getContentAsString();
        return "Bearer " + MAPPER.readTree(body).get("token").asText();
    }
}
