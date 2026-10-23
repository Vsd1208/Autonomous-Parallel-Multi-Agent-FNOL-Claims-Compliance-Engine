package com.guidewire.fnol.agents;

import com.guidewire.fnol.agents.EvidenceImageLoader.Download;
import com.guidewire.fnol.agents.EvidenceImageLoader.EvidenceRejectedException;
import com.guidewire.fnol.agents.EvidenceImageLoader.ImageData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** P7 / D5: evidence loading cannot be used to reach internal hosts or pull unbounded data. */
class EvidenceImageLoaderTest {

    private static final InetAddress PUBLIC_IP = ip(93, 184, 216, 34);
    private final AtomicInteger downloads = new AtomicInteger();

    private EvidenceImageLoader loader(InetAddress resolvesTo, Download response) {
        return new EvidenceImageLoader(List.of("images.example.com"),
                host -> new InetAddress[]{resolvesTo},
                uri -> {
                    downloads.incrementAndGet();
                    return response;
                });
    }

    private static Download ok(String contentType, byte[] body) {
        return new Download(200, contentType, body.length, new ByteArrayInputStream(body));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://127.0.0.1/photo.jpg",
            "http://169.254.169.254/latest/meta-data",
            "file:///etc/passwd",
            "mock://photo/front-bumper.jpg",
            "ftp://images.example.com/photo.jpg",
            "https://user:pass@images.example.com/photo.jpg",
            "https://images.example.com:8443/photo.jpg",
            "https://other.example.com/photo.jpg"
    })
    @DisplayName("Refuses non-https schemes, credentials, odd ports and hosts outside the allowlist without downloading")
    void refusesDisallowedUris(String uri) {
        EvidenceImageLoader loader = loader(PUBLIC_IP, ok("image/jpeg", new byte[]{1}));

        assertThatThrownBy(() -> loader.load(uri)).isInstanceOf(EvidenceRejectedException.class);
        assertThat(downloads).hasValue(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "169.254.169.254", "10.0.0.5", "172.16.3.4", "192.168.1.1",
            "100.64.0.1", "0.0.0.0", "::1", "fd00::1", "fe80::1"})
    @DisplayName("Refuses an allow-listed host that resolves to a loopback, link-local, private or unique-local address")
    void refusesNonPublicResolution(String address) throws Exception {
        EvidenceImageLoader loader = loader(InetAddress.getByName(address), ok("image/jpeg", new byte[]{1}));

        assertThatThrownBy(() -> loader.load("https://images.example.com/photo.jpg"))
                .isInstanceOf(EvidenceRejectedException.class)
                .hasMessageContaining("non-public");
        assertThat(downloads).hasValue(0);
    }

    @Test
    @DisplayName("Refuses a redirect instead of following it")
    void refusesRedirect() {
        EvidenceImageLoader loader = loader(PUBLIC_IP, new Download(302, null, 0, InputStream.nullInputStream()));

        assertThatThrownBy(() -> loader.load("https://images.example.com/photo.jpg"))
                .isInstanceOf(EvidenceRejectedException.class)
                .hasMessageContaining("redirect");
    }

    @Test
    @DisplayName("Refuses a body larger than 5 MB even when Content-Length is missing")
    void refusesOversizedBody() {
        byte[] big = new byte[EvidenceImageLoader.MAX_BYTES + 1];
        EvidenceImageLoader loader = loader(PUBLIC_IP,
                new Download(200, "image/jpeg", -1, new ByteArrayInputStream(big)));

        assertThatThrownBy(() -> loader.load("https://images.example.com/photo.jpg"))
                .isInstanceOf(EvidenceRejectedException.class)
                .hasMessageContaining("5 MB");
    }

    @Test
    @DisplayName("Refuses a non-image content type")
    void refusesNonImageContentType() {
        EvidenceImageLoader loader = loader(PUBLIC_IP, ok("text/html", "<html/>".getBytes()));

        assertThatThrownBy(() -> loader.load("https://images.example.com/photo.jpg"))
                .isInstanceOf(EvidenceRejectedException.class)
                .hasMessageContaining("image/*");
    }

    @Test
    @DisplayName("Accepts an image from an allow-listed host that resolves to a public address")
    void acceptsAllowedHost() throws Exception {
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
        ImageData image = loader(PUBLIC_IP, ok("image/jpeg; charset=binary", jpeg))
                .load("https://IMAGES.example.com/photo.jpg");

        assertThat(image.mimeType()).isEqualTo("image/jpeg");
        assertThat(Base64.getDecoder().decode(image.base64())).isEqualTo(jpeg);
    }

    @Test
    @DisplayName("Accepts a small image data URI without any network access")
    void acceptsSmallDataUri() throws Exception {
        ImageData image = loader(PUBLIC_IP, null).load("data:image/png;base64,iVBORw0KGgo=");

        assertThat(image.mimeType()).isEqualTo("image/png");
        assertThat(downloads).hasValue(0);
    }

    @Test
    @DisplayName("Refuses a data URI that is not an allowed image type or exceeds 5 MB")
    void refusesBadDataUris() {
        EvidenceImageLoader loader = loader(PUBLIC_IP, null);
        String huge = "data:image/png;base64," + Base64.getEncoder().encodeToString(new byte[EvidenceImageLoader.MAX_BYTES + 3]);

        assertThatThrownBy(() -> loader.load("data:text/html;base64,PGh0bWw+")).isInstanceOf(EvidenceRejectedException.class);
        assertThatThrownBy(() -> loader.load("data:image/svg+xml;base64,PHN2Zz4=")).isInstanceOf(EvidenceRejectedException.class);
        assertThatThrownBy(() -> loader.load(huge)).isInstanceOf(EvidenceRejectedException.class);
    }

    @Test
    @DisplayName("With the default (empty) allowlist no remote download is attempted")
    void emptyAllowlistMeansNoRemoteDownloads() {
        EvidenceImageLoader loader = new EvidenceImageLoader(List.of(""), host -> new InetAddress[]{PUBLIC_IP},
                uri -> { downloads.incrementAndGet(); return null; });

        assertThatThrownBy(() -> loader.load("https://images.example.com/photo.jpg"))
                .isInstanceOf(EvidenceRejectedException.class);
        assertThat(downloads).hasValue(0);
    }

    @Test
    @DisplayName("describe() never reveals the path, query or credentials")
    void describeIsSafeToLog() {
        assertThat(EvidenceImageLoader.describe("https://u:p@images.example.com/a/b.jpg?token=secret"))
                .isEqualTo("https://images.example.com");
        assertThat(EvidenceImageLoader.describe("data:image/png;base64,AAAA")).isEqualTo("data:image/png");
    }

    private static InetAddress ip(int a, int b, int c, int d) {
        try {
            return InetAddress.getByAddress(new byte[]{(byte) a, (byte) b, (byte) c, (byte) d});
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
