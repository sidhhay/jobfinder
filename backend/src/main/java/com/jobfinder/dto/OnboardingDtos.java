package com.jobfinder.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** Bodies of the onboarding and profile routes. */
public final class OnboardingDtos {

    private OnboardingDtos() {
    }

    public record ProfileUpdateRequest(
            @NotNull @Size(min = 1, max = 200) String rawJobTitle,
            @Size(max = 1000) String bio) {
    }

    public record SelectedTagsRequest(@NotEmpty List<@NotNull UUID> tagIds) {
    }

    public record Tag(String tagId, String tagName) {
    }

    public record OnboardingState(
            String userId, String rawJobTitle, String bio,
            List<Tag> recommendedTags, List<Tag> assignedTags, List<Tag> selectedTags) {
    }

    public record AccountUpdateRequest(
            @NotNull @Size(min = 1, max = 120) String fullName,
            @Size(max = 200) String rawJobTitle,
            @Size(max = 1000) String bio,
            @Size(max = 40) String phoneNumber,
            @Size(max = 300) String githubUrl,
            @Size(max = 300) String linkedinUrl,
            @Size(max = 300) String notionUrl,
            @Size(min = 8, max = 128) String password) {
    }
}
