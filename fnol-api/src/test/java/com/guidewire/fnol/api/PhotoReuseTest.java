package com.guidewire.fnol.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guidewire.fnol.common.Models.*;
import com.guidewire.fnol.enrichment.ClaimCenterClient;
import com.guidewire.fnol.enrichment.PolicyCenterClient;
import com.guidewire.fnol.orchestrator.IntakeReviewService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A photo already used on another claim is recognised, even resized and recompressed. */
@SpringBootTest
@AutoConfigureMockMvc
class PhotoReuseTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockBean private PolicyCenterClient policyCenterClient;
    @MockBean private ClaimCenterClient claimCenterClient;
    private final AtomicInteger ids = new AtomicInteger(900);

    /** A photo-like test image: gradients and shapes, so it has a real fingerprint. */
    private static BufferedImage scene(int seed, int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        for (int x = 0; x < w; x++) {
            g.setColor(new Color((x * 255 / w + seed * 40) % 256, (seed * 70) % 256, 255 - x * 255 / w));
            g.drawLine(x, 0, x, h);
        }
        g.setColor(Color.DARK_GRAY);
        g.fillOval(w / 5 + seed * 13 % (w / 3), h / 4, w / 3, h / 2);
        g.setColor(Color.WHITE);
        g.fillRect(w / 2, h / 6 + seed * 7 % (h / 3), w / 4, h / 5);
        g.dispose();
        return img;
    }

    private static String dataUri(BufferedImage img, String format) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, format, out);
        return "data:image/" + ("jpg".equals(format) ? "jpeg" : format) + ";base64,"
                + Base64.getEncoder().encodeToString(out.toByteArray());
    }

    private static BufferedImage resized(BufferedImage img, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(img, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    private JsonNode intake(String photo, int daysAgo) throws Exception {
        LocalDate start = LocalDate.now().minusMonths(6);
        when(policyCenterClient.getPolicy("POL-AUTO-112233")).thenReturn(new Policy("POL-AUTO-112233", "AUTO", "ACTIVE",
                start, start.plusYears(1), "Avery Johnson", "CA", "2025 Camry", List.of()));
        when(policyCenterClient.getCoverages("POL-AUTO-112233")).thenReturn(List.of(
                new Coverage("COLLISION", new BigDecimal("50000"), new BigDecimal("1000"))));
        when(claimCenterClient.createClaim(any())).thenAnswer(inv ->
                new ClaimCreateResponse("CLM-2026-%06d".formatted(ids.getAndIncrement()), "DRAFT_PENDING_REVIEW"));
        FNOLPayload payload = new FNOLPayload("POL-AUTO-112233", LocalDate.now().minusDays(daysAgo), "CA",
                "Hit a bollard while parking.", "Avery Johnson", null, null, List.of(photo), "WEB", null, null,
                new BigDecimal("2000"), List.of("CLOSE_UP"));
        String body = mockMvc.perform(post("/api/v1/fnol/intake").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    @Test
    @DisplayName("The same picture on a later claim, resized and re-encoded as JPEG, raises PHOTO_REUSED naming the first claim")
    void reusedPhotoIsFlagged() throws Exception {
        BufferedImage original = scene(3, 640, 480);
        JsonNode first = intake(dataUri(original, "png"), 20);
        assertThat(first.path("inputFlags").toString()).doesNotContain(IntakeReviewService.PHOTO_REUSED);

        JsonNode second = intake(dataUri(resized(original, 480, 360), "jpg"), 10);
        assertThat(second.path("inputFlags")).extracting(JsonNode::asText).contains(IntakeReviewService.PHOTO_REUSED);
        assertThat(second.path("status").asText()).isEqualTo("HUMAN_REVIEW");
        assertThat(second.path("notes").toString()).contains("PHOTO REUSED").contains(first.path("claimId").asText());

        // A different picture is not a match.
        JsonNode third = intake(dataUri(scene(11, 640, 480), "png"), 5);
        assertThat(third.path("inputFlags").toString()).doesNotContain(IntakeReviewService.PHOTO_REUSED);
    }

    @Test
    @DisplayName("Perceptual hash: same picture resized stays within 5 bits; flat or tiny images get no fingerprint")
    void perceptualHash() throws Exception {
        BufferedImage img = scene(5, 800, 600);
        ByteArrayOutputStream a = new ByteArrayOutputStream();
        ImageIO.write(img, "png", a);
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        ImageIO.write(resized(img, 320, 240), "jpg", b);
        String ha = PerceptualHash.of(a.toByteArray());
        String hb = PerceptualHash.of(b.toByteArray());
        assertThat(PerceptualHash.distance(ha, hb)).isLessThanOrEqualTo(PerceptualHash.MAX_DISTANCE);

        BufferedImage flat = new BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream c = new ByteArrayOutputStream();
        ImageIO.write(flat, "png", c);
        assertThat(PerceptualHash.of(c.toByteArray())).isNull();
        assertThat(PerceptualHash.of(new byte[]{1, 2, 3})).isNull();
    }
}
