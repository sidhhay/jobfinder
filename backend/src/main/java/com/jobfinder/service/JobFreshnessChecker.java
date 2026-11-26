package com.jobfinder.service;

import com.jobfinder.client.Http;
import com.jobfinder.util.Values;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;

/** Visits listing pages in parallel to find out whether jobs are still open. */
@Component
public class JobFreshnessChecker {

    /** Result for one listing; {@code staleReason} is {@code null} while it is open. */
    public record Freshness(String jobId, boolean active, String staleReason) {
    }

    private static final int MAX_PARALLEL = 6;
    private static final int BODY_LIMIT = 24_576;
    private static final Set<Integer> GONE = Set.of(404, 410, 451);
    private static final Pattern CLOSED = Pattern.compile(String.join("|",
                    "job (?:is )?no longer available",
                    "no longer accepting applications",
                    "applications (?:for this role )?(?:are )?closed",
                    "position has been filled",
                    "job has expired",
                    "role is closed",
                    "vacancy closed",
                    "not accepting applications",
                    "posting has expired",
                    "this job is unavailable"),
            Pattern.CASE_INSENSITIVE);

    private final Http http;

    public JobFreshnessChecker(Http http) {
        this.http = http;
    }

    /** Checks every row; rows whose state cannot be determined are left out. */
    public List<Freshness> check(List<Map<String, Object>> rows) {
        try (ExecutorService pool = Executors.newFixedThreadPool(MAX_PARALLEL)) {
            List<Future<Freshness>> futures = rows.stream()
                    .map(row -> pool.submit(() -> checkOne(row)))
                    .toList();
            return futures.stream().map(JobFreshnessChecker::await)
                    .filter(result -> result != null).toList();
        }
    }

    private static Freshness await(Future<Freshness> future) {
        try {
            return future.get();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException error) {
            return null;
        }
    }

    private Freshness checkOne(Map<String, Object> row) {
        String url = Values.firstNonBlank(Values.str(row.get("apply_url")), Values.str(row.get("source_url"))).strip();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return null;
        }
        String jobId = Values.str(row.get("job_id"));
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(8))
                    .header("User-Agent", "JobfulBot/1.0 (+job freshness check)")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .GET().build();
            HttpResponse<InputStream> response = http.stream(request);
            try (InputStream body = response.body()) {
                int status = response.statusCode();
                if (GONE.contains(status)) {
                    return new Freshness(jobId, false, "Listing returned HTTP " + status + ".");
                }
                if (status >= 400) {
                    return null;
                }
                String haystack = response.uri() + "\n"
                        + new String(body.readNBytes(BODY_LIMIT), StandardCharsets.UTF_8);
                return CLOSED.matcher(haystack).find()
                        ? new Freshness(jobId, false, "Listing appears closed or no longer accepting applications.")
                        : new Freshness(jobId, true, null);
            }
        } catch (IOException | IllegalArgumentException error) {
            return null;
        }
    }
}
