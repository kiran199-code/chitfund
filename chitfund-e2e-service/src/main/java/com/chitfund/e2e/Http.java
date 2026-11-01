package com.chitfund.e2e;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * One HTTP client with the behaviour the test service needs against free-tier services: it waits and retries while a sleeping service wakes
 * (connection errors, 502/503/504) and waits out a rate limit (429). Credentials are only ever put in the Authorization header; they are never
 * written to an exception message or a report.
 */
public final class Http {

    public record Resp(int status, String body, Map<String, List<String>> headers) {
        public JsonNode json() {
            return body == null || body.isBlank() ? Json.MAPPER.nullNode() : Json.read(body);
        }

        public boolean ok() {
            return status >= 200 && status < 300;
        }

        public List<String> headers(String name) {
            for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) {
                    return e.getValue();
                }
            }
            return List.of();
        }
    }

    /** A call that came back with an error status. {@link #code()} is the service's own error code when the body has one. */
    public static final class ApiException extends RuntimeException {
        public final int status;
        public final String method;
        public final String url;
        public final String bodySnippet;

        public ApiException(int status, String method, String url, String body) {
            super(method + " " + url + " -> HTTP " + status + (body == null || body.isBlank() ? "" : ": " + snippet(body)));
            this.status = status;
            this.method = method;
            this.url = url;
            this.bodySnippet = snippet(body);
        }

        public String code() {
            try {
                JsonNode n = Json.read(bodySnippet);
                String c = Json.text(n, "code");
                return c != null ? c : Json.text(n, "error");
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    static String snippet(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > 500 ? t.substring(0, 500) + "..." : t;
    }

    public interface Sleeper {
        void sleep(long millis);
    }

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    private final Duration requestTimeout;
    private final int maxRetries;
    private final long wakeWaitMillis;
    private final long rateLimitWaitMillis;
    private final Sleeper sleeper;

    public Http(Duration requestTimeout, int maxRetries, long wakeWaitMillis, long rateLimitWaitMillis, Sleeper sleeper) {
        this.requestTimeout = requestTimeout;
        this.maxRetries = maxRetries;
        this.wakeWaitMillis = wakeWaitMillis;
        this.rateLimitWaitMillis = rateLimitWaitMillis;
        this.sleeper = sleeper;
    }

    public static Http standard() {
        return new Http(Duration.ofSeconds(90), 6, 5_000, 20_000, ms -> {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted");
            }
        });
    }

    public static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    /** Sends the request; retries as described above; returns whatever final status came back (the caller decides what is an error). */
    public Resp send(String method, String url, Map<String, String> headers, String body) {
        int wakeTries = 0;
        int rateTries = 0;
        while (true) {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(requestTimeout);
            headers.forEach(b::header);
            b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
            Resp r;
            try {
                HttpResponse<String> resp = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
                r = new Resp(resp.statusCode(), resp.body(), resp.headers().map());
            } catch (IOException e) {
                if (++wakeTries > maxRetries) {
                    throw new IllegalStateException("cannot reach " + method + " " + url + " (" + e.getClass().getSimpleName() + ")");
                }
                sleeper.sleep(wakeWaitMillis * wakeTries);
                continue;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted");
            }
            if (r.status() == 429 && rateTries < 3) {
                rateTries++;
                sleeper.sleep(rateLimitWaitMillis);
                continue;
            }
            if ((r.status() == 502 || r.status() == 503 || r.status() == 504) && wakeTries < maxRetries) {
                wakeTries++;
                sleeper.sleep(wakeWaitMillis * wakeTries);
                continue;
            }
            return r;
        }
    }
}
