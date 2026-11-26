package com.jobfinder.client;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

/** Shared outbound HTTP client. */
@Component
public class Http {

    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** Sends the request and returns status plus the whole body as text. */
    public HttpResponse<String> send(HttpRequest request) throws IOException {
        return exchange(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /** Sends the request and leaves the body as a stream so the caller can read only part of it. */
    public HttpResponse<InputStream> stream(HttpRequest request) throws IOException {
        return exchange(request, HttpResponse.BodyHandlers.ofInputStream());
    }

    private <T> HttpResponse<T> exchange(HttpRequest request, HttpResponse.BodyHandler<T> handler)
            throws IOException {
        try {
            return client.send(request, handler);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Request interrupted", error);
        }
    }

    /** Builds an {@code application/x-www-form-urlencoded} query string. */
    public static String query(Map<String, ?> params) {
        return params.entrySet().stream()
                .map(e -> encode(e.getKey()) + "=" + encode(String.valueOf(e.getValue())))
                .collect(Collectors.joining("&"));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
