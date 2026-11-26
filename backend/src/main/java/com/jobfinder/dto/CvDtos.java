package com.jobfinder.dto;

import java.util.List;
import java.util.Objects;

/** Parsed CV data exchanged during the preview and confirm steps. */
public final class CvDtos {

    private CvDtos() {
    }

    public record Education(
            String institution, String degree, String degreeLevel, String fieldOfStudy, String startDate,
            String endDate, String grade, String description, String tag) {

        public Education {
            institution = Objects.requireNonNullElse(institution, "");
            degree = Objects.requireNonNullElse(degree, "");
            degreeLevel = Objects.requireNonNullElse(degreeLevel, "ug");
            tag = Objects.requireNonNullElse(tag, "research");
        }
    }

    public record Experience(
            String company, String location, String role, String experienceType, String startDate,
            String endDate, String description, String tag, List<String> keywords) {

        public Experience {
            company = Objects.requireNonNullElse(company, "");
            role = Objects.requireNonNullElse(role, "");
            experienceType = Objects.requireNonNullElse(experienceType, "full_time");
            startDate = Objects.requireNonNullElse(startDate, "");
            keywords = Objects.requireNonNullElse(keywords, List.of());
        }
    }

    public record Project(
            String name, String description, String githubUrl, String demoUrl, String tag, List<String> keywords) {

        public Project {
            name = Objects.requireNonNullElse(name, "");
            keywords = Objects.requireNonNullElse(keywords, List.of());
        }
    }

    public record Contact(
            String email, String phoneNumber, String githubUrl, String linkedinUrl, String notionUrl) {
    }

    public record Preview(
            List<Education> education, List<Experience> experiences, List<Project> projects,
            List<String> suggestedTags, Contact contactDetails) {
    }

    public record ConfirmRequest(
            List<Education> education, List<Experience> experiences, List<Project> projects,
            List<String> suggestedTags) {

        public ConfirmRequest {
            education = Objects.requireNonNullElse(education, List.of());
            experiences = Objects.requireNonNullElse(experiences, List.of());
            projects = Objects.requireNonNullElse(projects, List.of());
            suggestedTags = Objects.requireNonNullElse(suggestedTags, List.of());
        }
    }

    public record ConfirmResponse(
            int educationStored, int experiencesStored, int projectsStored, List<String> suggestedTags) {
    }
}
