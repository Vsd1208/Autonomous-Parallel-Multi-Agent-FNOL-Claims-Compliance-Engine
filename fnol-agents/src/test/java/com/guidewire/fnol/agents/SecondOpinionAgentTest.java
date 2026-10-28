package com.guidewire.fnol.agents;

import com.guidewire.fnol.common.Models.FNOLPayload;
import com.guidewire.fnol.common.Models.SecondOpinion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class SecondOpinionAgentTest {

    private static final String PHOTO = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";

    private static FNOLPayload payload(List<String> uris, List<String> angles) {
        return new FNOLPayload("POL-AUTO-112233", LocalDate.now().minusDays(2), "CA", "Hit a pole.", "Avery Johnson",
                null, null, uris, "WEB", null, null, null, angles);
    }

    @Test
    @DisplayName("Sends the uploaded photos with their angles and the line; parses both probability maps")
    void asksTheServiceAboutUploadedPhotos() throws Exception {
        RestTemplate rest = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
        server.expect(requestTo("http://vision-ml:8090/v1/second-opinion"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.line").value("HOME"))
                .andExpect(jsonPath("$.images.length()").value(2))
                .andExpect(jsonPath("$.angles[1]").value("CLOSE_UP"))
                .andRespond(withSuccess("""
                        {"model": "second-opinion v1", "damageProbabilities": {"FLOOD": 0.81, "WIND_STORM": 0.19},
                         "subjectProbabilities": {"BUILDING": 0.93, "VEHICLE": 0.07}}""", MediaType.APPLICATION_JSON));
        SecondOpinionAgent agent = new SecondOpinionAgent("http://vision-ml:8090", rest, new EvidenceImageLoader(List.of()));

        SecondOpinion so = agent.start(payload(List.of(PHOTO, "mock://photo/x.jpg", PHOTO),
                List.of("OVERVIEW", "OTHER", "CLOSE_UP")), "HOME").get();

        server.verify();
        assertThat(so.model()).isEqualTo("second-opinion v1");
        assertThat(so.probabilityOf("FLOOD")).isEqualByComparingTo("0.81");
        assertThat(so.probabilityOf("FRONT_BUMPER")).isNull();
        assertThat(so.subjectProbabilities()).containsKey("BUILDING");
    }

    @Test
    @DisplayName("Best effort: disabled, no usable photo, or a failing service all give null, never an error")
    void neverFailsTheClaim() throws Exception {
        assertThat(SecondOpinionAgent.disabled().start(payload(List.of(PHOTO), List.of("FRONT")), "AUTO").get()).isNull();

        RestTemplate rest = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
        SecondOpinionAgent agent = new SecondOpinionAgent("http://vision-ml:8090", rest, new EvidenceImageLoader(List.of()));
        // mock:// and http: evidence is refused by the loader, so nothing is sent.
        assertThat(agent.start(payload(List.of("mock://photo/a.jpg", "http://example.com/b.jpg"), List.of()), "AUTO").get())
                .isNull();
        server.verify();

        server.expect(requestTo("http://vision-ml:8090/v1/second-opinion")).andRespond(withServerError());
        assertThat(agent.start(payload(List.of(PHOTO), List.of("FRONT")), "AUTO").get()).isNull();
    }
}
