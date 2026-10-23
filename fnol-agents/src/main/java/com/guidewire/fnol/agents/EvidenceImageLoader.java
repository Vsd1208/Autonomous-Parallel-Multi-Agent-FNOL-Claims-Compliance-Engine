package com.guidewire.fnol.agents;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns an evidence URI into image bytes for the vision model without opening an SSRF hole.
 *
 * <p>Accepted:
 * <ul>
 *   <li>{@code data:image/(jpeg|png|webp|gif);base64,...} up to 5 MB decoded.</li>
 *   <li>{@code https://} URLs whose host is listed in {@code fnol.evidence.allowed-hosts}
 *       (default empty: no remote downloads), that resolve only to public addresses, answer
 *       200 without a redirect, send an {@code image/*} content type and at most 5 MB.</li>
 * </ul>
 * Everything else ({@code http:}, {@code file:}, {@code mock:}, loopback, link-local such as
 * 169.254.169.254, private and unique-local ranges) is refused with {@link EvidenceRejectedException}.
 *
 * <p>Residual risk: the host is resolved once for the check and again by the HTTP client, so a
 * DNS-rebinding host could still differ between the two. The allowlist is the primary control.
 */
@Component
public class EvidenceImageLoader {

    public static final int MAX_BYTES = 5 * 1024 * 1024;

    private static final Pattern DATA_URI = Pattern.compile(
            "^data:(image/(?:jpeg|png|webp|gif));base64,([A-Za-z0-9+/=]+)$", Pattern.CASE_INSENSITIVE);

    @FunctionalInterface
    public interface HostResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    @FunctionalInterface
    public interface ImageDownloader {
        Download open(URI uri) throws IOException, InterruptedException;
    }

    /** An HTTP response the loader still has to validate. */
    public record Download(int status, String contentType, long contentLength, InputStream body) implements Closeable {
        @Override
        public void close() throws IOException {
            if (body != null) body.close();
        }
    }

    public record ImageData(String mimeType, String base64) {}

    public static class EvidenceRejectedException extends Exception {
        public EvidenceRejectedException(String reason) {
            super(reason);
        }
    }

    private final Set<String> allowedHosts;
    private final HostResolver resolver;
    private final ImageDownloader downloader;

    @Autowired
    public EvidenceImageLoader(@Value("${fnol.evidence.allowed-hosts:}") List<String> allowedHosts) {
        this(allowedHosts, InetAddress::getAllByName, httpDownloader());
    }

    public EvidenceImageLoader(Collection<String> allowedHosts, HostResolver resolver, ImageDownloader downloader) {
        Set<String> hosts = new HashSet<>();
        for (String h : allowedHosts) {
            if (h != null && !h.isBlank()) hosts.add(h.trim().toLowerCase(Locale.ROOT));
        }
        this.allowedHosts = Set.copyOf(hosts);
        this.resolver = resolver;
        this.downloader = downloader;
    }

    public ImageData load(String uri) throws EvidenceRejectedException {
        if (uri == null || uri.isBlank()) {
            throw new EvidenceRejectedException("empty evidence URI");
        }
        if (uri.regionMatches(true, 0, "data:", 0, 5)) {
            return fromDataUri(uri);
        }
        return download(parseHttpsUri(uri));
    }

    /** Scheme and host only, safe to log (no path, query or credentials). */
    public static String describe(String uri) {
        if (uri == null) return "null";
        if (uri.regionMatches(true, 0, "data:", 0, 5)) {
            int semi = uri.indexOf(';');
            return semi > 0 && semi < 40 ? uri.substring(0, semi) : "data:";
        }
        try {
            URI u = new URI(uri);
            return u.getScheme() + "://" + u.getHost();
        } catch (URISyntaxException e) {
            return "unparseable-uri";
        }
    }

    private ImageData fromDataUri(String uri) throws EvidenceRejectedException {
        Matcher m = DATA_URI.matcher(uri);
        if (!m.matches()) {
            throw new EvidenceRejectedException("data URI must be data:image/(jpeg|png|webp|gif);base64");
        }
        String base64 = m.group(2);
        if ((long) base64.length() * 3 / 4 > MAX_BYTES + 2) {
            throw new EvidenceRejectedException("data URI image exceeds 5 MB");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new EvidenceRejectedException("data URI is not valid base64");
        }
        if (decoded.length > MAX_BYTES) {
            throw new EvidenceRejectedException("data URI image exceeds 5 MB");
        }
        return new ImageData(m.group(1).toLowerCase(Locale.ROOT), base64);
    }

    private URI parseHttpsUri(String uri) throws EvidenceRejectedException {
        URI u;
        try {
            u = new URI(uri);
        } catch (URISyntaxException e) {
            throw new EvidenceRejectedException("evidence URI is not a valid URI");
        }
        if (!"https".equalsIgnoreCase(u.getScheme())) {
            throw new EvidenceRejectedException("only https evidence URLs are downloaded (got " + u.getScheme() + ")");
        }
        String host = u.getHost();
        if (host == null || u.getUserInfo() != null) {
            throw new EvidenceRejectedException("evidence URL must have a host and no credentials");
        }
        if (u.getPort() != -1 && u.getPort() != 443) {
            throw new EvidenceRejectedException("evidence URL must use port 443");
        }
        if (!allowedHosts.contains(host.toLowerCase(Locale.ROOT))) {
            throw new EvidenceRejectedException("host " + host + " is not in fnol.evidence.allowed-hosts");
        }
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(host);
        } catch (UnknownHostException e) {
            throw new EvidenceRejectedException("host " + host + " does not resolve");
        }
        if (addresses == null || addresses.length == 0) {
            throw new EvidenceRejectedException("host " + host + " does not resolve");
        }
        for (InetAddress a : addresses) {
            if (!isPublic(a)) {
                throw new EvidenceRejectedException("host " + host + " resolves to a non-public address");
            }
        }
        return u;
    }

    private ImageData download(URI uri) throws EvidenceRejectedException {
        try (Download d = downloader.open(uri)) {
            if (d.status() >= 300 && d.status() < 400) {
                throw new EvidenceRejectedException("evidence URL redirected; redirects are not followed");
            }
            if (d.status() != 200) {
                throw new EvidenceRejectedException("evidence URL returned HTTP " + d.status());
            }
            String mime = d.contentType() == null ? "" : d.contentType().split(";")[0].trim().toLowerCase(Locale.ROOT);
            if (!mime.startsWith("image/")) {
                throw new EvidenceRejectedException("evidence URL content type is not image/*");
            }
            if (d.contentLength() > MAX_BYTES) {
                throw new EvidenceRejectedException("evidence image exceeds 5 MB");
            }
            byte[] bytes = readCapped(d.body());
            return new ImageData(mime, Base64.getEncoder().encodeToString(bytes));
        } catch (IOException e) {
            throw new EvidenceRejectedException("evidence download failed: " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EvidenceRejectedException("evidence download interrupted");
        }
    }

    private static byte[] readCapped(InputStream in) throws IOException, EvidenceRejectedException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > MAX_BYTES) {
                throw new EvidenceRejectedException("evidence image exceeds 5 MB");
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    static boolean isPublic(InetAddress a) {
        if (a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()
                || a.isAnyLocalAddress() || a.isMulticastAddress()) {
            return false;
        }
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address) {
            int first = b[0] & 0xff;
            int second = b[1] & 0xff;
            if (first == 0) return false;                                   // 0.0.0.0/8
            if (first == 100 && second >= 64 && second <= 127) return false; // 100.64.0.0/10 carrier-grade NAT
        } else if (a instanceof Inet6Address) {
            if ((b[0] & 0xfe) == 0xfc) return false;                        // fc00::/7 unique-local
        }
        return true;
    }

    private static ImageDownloader httpDownloader() {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        return uri -> {
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).GET().build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            return new Download(response.statusCode(),
                    response.headers().firstValue("Content-Type").orElse(null),
                    response.headers().firstValueAsLong("Content-Length").orElse(-1),
                    response.body());
        };
    }
}
