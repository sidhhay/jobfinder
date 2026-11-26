package com.jobfinder.service;

import com.jobfinder.client.Http;
import com.jobfinder.config.AppSettings;
import com.jobfinder.util.Values;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Supabase Storage uploads and deletions for generated PDFs. */
@Service
public class StorageService {

    /** The upload did not happen; the message is stored with the document. */
    public static class StorageException extends RuntimeException {
        public StorageException(String message) {
            super(message);
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(StorageService.class);

    private final AppSettings settings;
    private final Http http;

    public StorageService(AppSettings settings, Http http) {
        this.settings = settings;
        this.http = http;
    }

    public void upload(String bucket, String path, byte[] pdf) {
        if (!configured()) {
            throw new StorageException("Supabase storage is not configured.");
        }
        try {
            HttpRequest request = request(bucket, path, Duration.ofSeconds(60))
                    .header("Content-Type", "application/pdf")
                    .header("x-upsert", "true")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(pdf))
                    .build();
            HttpResponse<String> response = http.send(request);
            if (response.statusCode() >= 400) {
                throw new StorageException("Storage upload failed: " + Values.truncate(response.body(), 300));
            }
        } catch (IOException | IllegalArgumentException error) {
            throw new StorageException("Storage upload failed: " + error.getMessage());
        }
    }

    /** Best effort removal: failures are logged and ignored. */
    public void delete(String bucket, String path) {
        if (!configured()) {
            return;
        }
        try {
            http.send(request(bucket, path, Duration.ofSeconds(30)).DELETE().build());
        } catch (IOException | IllegalArgumentException error) {
            LOG.warn("Could not delete {} from storage: {}", path, error.getMessage());
        }
    }

    private boolean configured() {
        return settings.supabaseUrl() != null && settings.supabaseServiceRoleKey() != null;
    }

    private HttpRequest.Builder request(String bucket, String path, Duration timeout) {
        String base = settings.supabaseUrl().replaceAll("/+$", "");
        String key = settings.supabaseServiceRoleKey();
        return HttpRequest.newBuilder(
                        URI.create(base + "/storage/v1/object/" + bucket + "/" + path.replace(" ", "%20")))
                .timeout(timeout)
                .header("Authorization", "Bearer " + key)
                .header("apikey", key);
    }
}
