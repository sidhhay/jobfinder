package com.jobfinder.service;

import com.jobfinder.db.Database;
import com.jobfinder.dto.ApplicationDtos.Notification;
import com.jobfinder.dto.ApplicationDtos.Overview;
import com.jobfinder.dto.ApplicationDtos.PrepareApplyResponse;
import com.jobfinder.dto.ApplicationDtos.RemoveResponse;
import com.jobfinder.dto.ApplicationDtos.StatusResponse;
import com.jobfinder.dto.ApplicationDtos.Summary;
import com.jobfinder.dto.ApplicationDtos.TrackJobRequest;
import com.jobfinder.util.Values;
import com.jobfinder.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** Application tracking: pipeline overview, status changes and stale listing handling. */
@Service
public class ApplicationService {

    private static final Duration FRESHNESS_TTL = Duration.ofHours(1);
    private static final Set<String> SUBMITTED = Set.of("applied", "response", "placed");

    private final Database db;
    private final JobFreshnessChecker freshness;

    public ApplicationService(Database db, JobFreshnessChecker freshness) {
        this.db = db;
        this.freshness = freshness;
    }

    public List<Map<String, Object>> list(String userId) {
        return db.fetchAll("applications/get_user_applications", userId);
    }

    /** One application with its job; throws 404 when it does not belong to the user. */
    public Map<String, Object> require(String userId, String applicationId) {
        requireUuid(applicationId);
        Map<String, Object> row = db.fetchOne("applications/get_application_detail", applicationId, userId);
        if (row == null) {
            throw notFound();
        }
        return row;
    }

    /** {@link #require} without the internal owner column. */
    public Map<String, Object> detail(String userId, String applicationId) {
        return publicView(require(userId, applicationId));
    }

    public Overview overview(String userId) {
        List<Map<String, Object>> items = refreshFreshness(userId).stream()
                .map(ApplicationService::publicView).toList();
        List<Notification> notifications = items.stream()
                .filter(item -> !isActive(item))
                .map(ApplicationService::notification)
                .sorted(Comparator.comparing(
                        (Notification n) -> Values.str(n.staleDetectedAt())).reversed())
                .toList();
        Summary summary = new Summary(
                items.size(),
                count(items, ApplicationService::isActive),
                notifications.size(),
                count(items, item -> "interested".equals(item.get("status"))),
                count(items, item -> "applying".equals(item.get("status"))),
                count(items, item -> SUBMITTED.contains(Values.str(item.get("status")))));
        return new Overview(items, notifications, summary, OffsetDateTime.now(ZoneOffset.UTC).toString());
    }

    /** Saves a snapshot of the job and creates or updates the user's application for it. */
    public StatusResponse track(String userId, TrackJobRequest request) {
        return db.inTransaction(() -> {
            // Sources such as himalayas/remotive/jobicy are not seeded; register them on first use so the
            // tracked job keeps its source and the Jobs page can match it back to search results.
            Map<String, Object> source = db.fetchOne("jobs/get_source_id", request.source());
            if (source == null && !request.source().isBlank()) {
                source = db.fetchOne("jobs/ensure_source", request.source());
            }
            OffsetDateTime postedAt = Values.parseTimestamp(request.postedAt());
            Map<String, Object> job = db.fetchOne("jobs/upsert_job",
                    source == null ? null : source.get("source_id"),
                    request.title(),
                    request.company(),
                    request.description(),
                    Values.nullIfEmpty(request.location()),
                    Values.nullIfEmpty(request.salaryRange()),
                    Values.nullIfEmpty(Values.firstNonBlank(request.applyUrl(), request.sourceUrl())),
                    Values.nullIfEmpty(Values.firstNonBlank(request.sourceUrl(), request.applyUrl())),
                    Values.nullIfEmpty(request.externalId()),
                    postedAt == null ? OffsetDateTime.now(ZoneOffset.UTC) : postedAt);
            if (job == null) {
                throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to store job");
            }
            Map<String, Object> application = db.fetchOne("applications/create_application",
                    userId, job.get("job_id"), request.status());
            if (application == null) {
                throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to create application");
            }
            return statusResponse(application);
        });
    }

    public StatusResponse updateStatus(String userId, String applicationId, String status) {
        requireUuid(applicationId);
        Map<String, Object> row = db.fetchOne("applications/update_application_status",
                status, applicationId, userId);
        if (row == null) {
            throw notFound();
        }
        return statusResponse(row);
    }

    public PrepareApplyResponse prepareApply(String userId, String applicationId) {
        Map<String, Object> application = require(userId, applicationId);
        if (!isActive(application)) {
            throw new ApiException(HttpStatus.CONFLICT, "This role is no longer accepting applications.");
        }
        String status = Values.str(application.get("status"));
        if (status.equals("interested")) {
            updateStatus(userId, applicationId, "applying");
            status = "applying";
        }
        String applyUrl = Values.firstNonBlank(
                Values.str(application.get("apply_url")), Values.str(application.get("source_url")));
        return new PrepareApplyResponse(applicationId, status, "placeholder_pending",
                Values.nullIfEmpty(applyUrl), List.of("cv", "cover_letter"));
    }

    public RemoveResponse removeStale(String userId, String applicationId) {
        Map<String, Object> application = require(userId, applicationId);
        if (!"interested".equals(application.get("status")) || isActive(application)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Only stale interested jobs can be removed right now.");
        }
        return db.inTransaction(() -> {
            Map<String, Object> deleted = db.fetchOne("applications/delete_application", applicationId, userId);
            if (deleted == null) {
                throw notFound();
            }
            Map<String, Object> job = db.fetchOne("jobs/delete_orphan_job", deleted.get("job_id"));
            return new RemoveResponse(applicationId, job == null ? null : Values.str(job.get("job_id")));
        });
    }

    /** Re-checks listings not verified within the last hour, then returns the current rows. */
    private List<Map<String, Object>> refreshFreshness(String userId) {
        List<Map<String, Object>> rows = list(userId);
        List<Map<String, Object>> expired = rows.stream().filter(row -> {
            OffsetDateTime checked = Values.parseTimestamp(Values.str(row.get("last_checked_at")));
            return checked == null
                    || Duration.between(checked, OffsetDateTime.now()).compareTo(FRESHNESS_TTL) >= 0;
        }).toList();
        if (expired.isEmpty()) {
            return rows;
        }
        List<JobFreshnessChecker.Freshness> results = freshness.check(expired);
        if (results.isEmpty()) {
            return rows;
        }
        results.forEach(result -> db.execute("jobs/update_job_freshness",
                result.active(), result.staleReason(), result.active(), result.jobId()));
        return list(userId);
    }

    private static Map<String, Object> publicView(Map<String, Object> row) {
        Map<String, Object> view = new LinkedHashMap<>(row);
        view.remove("user_id");
        return view;
    }

    private static boolean isActive(Map<String, Object> item) {
        return !Boolean.FALSE.equals(item.get("is_active"));
    }

    private static int count(List<Map<String, Object>> items, Predicate<Map<String, Object>> test) {
        return (int) items.stream().filter(test).count();
    }

    private static Notification notification(Map<String, Object> item) {
        String status = Values.str(item.get("status"));
        boolean interested = status.equals("interested");
        return new Notification(
                Values.str(item.get("application_id")),
                Values.str(item.get("job_id")),
                status,
                interested ? "interested_stale" : "applied_stale",
                Values.firstNonBlank(Values.str(item.get("title")), "Unknown role"),
                Values.firstNonBlank(Values.str(item.get("company")), "Unknown company"),
                interested
                        ? "This tracked role is no longer accepting applications. "
                                + "Remove it to keep your dashboard current."
                        : "A role in your application pipeline is now stale or closed. No action is wired yet.",
                (String) item.get("stale_reason"),
                (String) item.get("stale_detected_at"),
                interested);
    }

    private static StatusResponse statusResponse(Map<String, Object> row) {
        return new StatusResponse(Values.str(row.get("application_id")), Values.str(row.get("status")));
    }

    private static void requireUuid(String value) {
        if (!value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            throw notFound();
        }
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "Application not found or not yours");
    }
}
