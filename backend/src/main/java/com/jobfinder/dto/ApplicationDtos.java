package com.jobfinder.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Bodies of the application tracking and document routes. */
public final class ApplicationDtos {

    public static final String STATUS_RULE = "interested|applying|applied|response|placed";
    public static final String DOCUMENT_RULE = "cv|cover_letter";

    private ApplicationDtos() {
    }

    public record TrackJobRequest(
            @NotNull String source, @NotNull String externalId, @NotNull String title, @NotNull String company,
            String location, String description, String applyUrl, String sourceUrl, String salaryRange,
            String postedAt, @Pattern(regexp = STATUS_RULE) String status) {

        public TrackJobRequest {
            location = Objects.requireNonNullElse(location, "");
            description = Objects.requireNonNullElse(description, "");
            applyUrl = Objects.requireNonNullElse(applyUrl, "");
            sourceUrl = Objects.requireNonNullElse(sourceUrl, "");
            postedAt = Objects.requireNonNullElse(postedAt, "");
            status = Objects.requireNonNullElse(status, "interested");
        }
    }

    public record UpdateStatusRequest(@NotNull @Pattern(regexp = STATUS_RULE) String status) {
    }

    public record StatusResponse(String applicationId, String status) {
    }

    public record PrepareApplyResponse(
            String applicationId, String status, String generationStatus, String applyUrl,
            List<String> documentsToGenerate) {
    }

    public record RemoveResponse(String applicationId, String removedJobId) {
    }

    public record Notification(
            String applicationId, String jobId, String status, String kind, String title, String company,
            String message, String staleReason, String staleDetectedAt, boolean canRemove) {
    }

    public record Summary(int total, int active, int stale, int interested, int applying, int applied) {
    }

    public record Overview(
            List<Map<String, Object>> items, List<Notification> notifications, Summary summary, String syncedAt) {
    }

    public record GenerateDocumentRequest(@NotNull @Pattern(regexp = DOCUMENT_RULE) String documentType) {
    }

    public record CompileDocumentRequest(
            @NotNull String documentId,
            @NotNull @Pattern(regexp = DOCUMENT_RULE) String documentType,
            @NotNull String html) {
    }

    public record GeneratedDocument(
            String documentId, String applicationId, String documentType, String title, String html,
            Map<String, Object> generationParams, String status) {
    }
}
