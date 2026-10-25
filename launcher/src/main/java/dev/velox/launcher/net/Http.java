package dev.velox.launcher.net;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Small HTTP layer: JSON GET/POST, form POST, and verified file downloads.
 *
 * <p>Downloads always go to a temp file, are hashed while streaming, and are moved into place only
 * after the hash matches. Interrupted or tampered downloads never leave a half-written or
 * unverified file where the game would load it.
 */
public final class Http {
    public static final String USER_AGENT = "velox-launcher/0.1.0 (github.com/velox-client/velox)";
    private static final Gson GSON = new com.google.gson.GsonBuilder().disableHtmlEscaping().create();

    private final HttpClient client;
    private final int maxAttempts;

    public Http() {
        this(3);
    }

    public Http(int maxAttempts) {
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.maxAttempts = maxAttempts;
    }

    public static final class HttpStatusException extends IOException {
        public final int status;
        public final String body;

        public HttpStatusException(String method, String url, int status, String body) {
            super(method + " " + url + " -> HTTP " + status + (body == null || body.isBlank() ? "" : ": " + abbreviate(body)));
            this.status = status;
            this.body = body;
        }

        private static String abbreviate(String s) {
            s = s.replaceAll("\\s+", " ");
            return s.length() > 300 ? s.substring(0, 300) + "…" : s;
        }
    }

    public JsonElement getJson(String url) throws IOException {
        return JsonParser.parseString(getString(url, Map.of()));
    }

    public <T> T getJson(String url, Class<T> type) throws IOException {
        return GSON.fromJson(getString(url, Map.of()), type);
    }

    public String getString(String url, Map<String, String> headers) throws IOException {
        HttpRequest.Builder b = request(url).GET();
        headers.forEach(b::header);
        return send(b.build(), "GET", url);
    }

    public JsonElement postJson(String url, Object body, Map<String, String> headers) throws IOException {
        HttpRequest.Builder b = request(url)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)));
        headers.forEach(b::header);
        return JsonParser.parseString(send(b.build(), "POST", url));
    }

    /** Form POST; returns the response status and body without throwing on 4xx (OAuth polling needs them). */
    public Response postForm(String url, Map<String, String> form) throws IOException {
        StringJoiner sj = new StringJoiner("&");
        form.forEach((k, v) -> sj.add(URLEncoder.encode(k, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(v, StandardCharsets.UTF_8)));
        HttpRequest req = request(url)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(sj.toString()))
                .build();
        HttpResponse<String> r = sendRaw(req, "POST", url);
        return new Response(r.statusCode(), r.body());
    }

    public record Response(int status, String body) {
        public JsonElement json() {
            return JsonParser.parseString(body);
        }
    }

    private HttpRequest.Builder request(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("User-Agent", USER_AGENT);
    }

    private String send(HttpRequest req, String method, String url) throws IOException {
        HttpResponse<String> r = sendRaw(req, method, url);
        if (r.statusCode() / 100 != 2) throw new HttpStatusException(method, url, r.statusCode(), r.body());
        return r.body();
    }

    private HttpResponse<String> sendRaw(HttpRequest req, String method, String url) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                HttpResponse<String> r = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                // Retry only what is plausibly transient: 429 and 5xx.
                if ((r.statusCode() == 429 || r.statusCode() >= 500) && attempt < maxAttempts) {
                    backoff(attempt);
                    continue;
                }
                return r;
            } catch (IOException e) {
                last = e;
                if (attempt < maxAttempts) backoff(attempt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted during " + method + " " + url, e);
            }
        }
        throw new IOException(method + " " + url + " failed after " + maxAttempts + " attempts: " + last.getMessage(), last);
    }

    private static void backoff(int attempt) throws IOException {
        try {
            Thread.sleep(500L << (attempt - 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
    }

    /** Expected content of a download. {@code algorithm} is a MessageDigest name, or null to skip hashing. */
    public record Expect(String algorithm, String hex, long size) {
        public static Expect sha1(String hex, long size) { return new Expect("SHA-1", hex, size); }
        public static Expect sha512(String hex, long size) { return new Expect("SHA-512", hex, size); }
        public static Expect sizeOnly(long size) { return new Expect(null, null, size); }
    }

    /**
     * Downloads {@code url} to {@code target}, verifying size and hash.
     *
     * @return number of bytes written
     */
    public long download(String url, Path target, Expect expect) throws IOException {
        Files.createDirectories(target.toAbsolutePath().getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".part");
        IOException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                long n = downloadOnce(url, tmp, expect);
                try {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
                return n;
            } catch (IntegrityException e) {
                Files.deleteIfExists(tmp);
                throw e; // a wrong hash will not fix itself by retrying
            } catch (HttpStatusException e) {
                Files.deleteIfExists(tmp);
                if (e.status != 429 && e.status < 500) throw e;
                last = e;
            } catch (IOException e) {
                Files.deleteIfExists(tmp);
                last = e;
            }
            if (attempt < maxAttempts) backoff(attempt);
        }
        throw new IOException("Download failed after " + maxAttempts + " attempts: " + url + ": " + last.getMessage(), last);
    }

    public static final class IntegrityException extends IOException {
        public IntegrityException(String msg) {
            super(msg);
        }
    }

    private long downloadOnce(String url, Path tmp, Expect expect) throws IOException {
        HttpRequest req = request(url).GET().build();
        HttpResponse<InputStream> r;
        try {
            r = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted downloading " + url, e);
        }
        if (r.statusCode() / 100 != 2) {
            try (InputStream in = r.body()) {
                String body = new String(in.readNBytes(2048), StandardCharsets.UTF_8);
                throw new HttpStatusException("GET", url, r.statusCode(), body);
            }
        }
        MessageDigest md = expect != null && expect.algorithm() != null ? digest(expect.algorithm()) : null;
        long total = 0;
        try (InputStream in = r.body(); OutputStream out = Files.newOutputStream(tmp)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (md != null) md.update(buf, 0, n);
                total += n;
            }
        }
        if (expect != null && expect.size() > 0 && total != expect.size()) {
            throw new IntegrityException("Size mismatch for " + url + ": expected " + expect.size() + " bytes, got " + total);
        }
        if (md != null && expect.hex() != null) {
            String actual = HexFormat.of().formatHex(md.digest());
            if (!actual.equalsIgnoreCase(expect.hex())) {
                throw new IntegrityException(expect.algorithm() + " mismatch for " + url + ": expected " + expect.hex() + ", got " + actual);
            }
        }
        return total;
    }

    public static MessageDigest digest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String hashFile(Path file, String algorithm) throws IOException {
        MessageDigest md = digest(algorithm);
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        return HexFormat.of().formatHex(md.digest());
    }

    /** True if {@code file} exists and matches {@code expect} (hash checked when given). */
    public static boolean matches(Path file, Expect expect) throws IOException {
        if (!Files.isRegularFile(file)) return false;
        if (expect.size() > 0 && Files.size(file) != expect.size()) return false;
        if (expect.algorithm() == null || expect.hex() == null) return true;
        return hashFile(file, expect.algorithm()).equalsIgnoreCase(expect.hex());
    }
}
