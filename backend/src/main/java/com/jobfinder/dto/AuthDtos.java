package com.jobfinder.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Map;
import java.util.Objects;

/** Request and response bodies of the authentication routes. */
public final class AuthDtos {

    /** An address needs a dot in its domain part. */
    private static final String DOMAIN_RULE = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$";

    private AuthDtos() {
    }

    /** The two possible answers of the register and login routes. */
    public sealed interface AuthResponse permits SessionResponse, VerificationChallenge {
    }

    public record RegisterRequest(
            @NotNull @Size(min = 1, max = 120) String fullName,
            @NotNull @Email @Pattern(regexp = DOMAIN_RULE) String email,
            @NotNull @Size(min = 8, max = 128) String password,
            @Size(max = 200) String rawJobTitle,
            @Size(max = 1000) String bio) {
    }

    public record LoginRequest(
            @NotNull @Email @Pattern(regexp = DOMAIN_RULE) String email,
            @NotNull String password) {
    }

    public record RefreshRequest(@NotNull String refreshToken) {
    }

    public record VerifyEmailRequest(
            @NotNull @Email @Pattern(regexp = DOMAIN_RULE) String email,
            @NotNull @Size(min = 1, max = 120) String fullName,
            @NotNull @Size(min = 8, max = 128) String password,
            @Size(max = 200) String rawJobTitle,
            @Size(max = 1000) String bio,
            @NotNull @Size(min = 4, max = 12) String code,
            @NotNull String verificationToken) {
    }

    public record ResendVerificationRequest(
            @NotNull @Email @Pattern(regexp = DOMAIN_RULE) String email,
            @NotNull @Size(min = 1, max = 120) String fullName,
            @NotNull String verificationToken) {
    }

    public record UserResponse(
            String userId, String email, String fullName, String rawJobTitle, String bio, String phoneNumber,
            String githubUrl, String linkedinUrl, String notionUrl, String emailVerifiedAt,
            int selectedTagsCount, boolean onboardingComplete, boolean cvUploaded, int experienceYears) {

        /** Builds the response from a profile map. */
        public static UserResponse from(Map<String, Object> user) {
            return new UserResponse(
                    text(user, "user_id"), text(user, "email"), text(user, "full_name"),
                    Objects.requireNonNullElse(text(user, "raw_job_title"), ""),
                    text(user, "bio"), text(user, "phone_number"), text(user, "github_url"),
                    text(user, "linkedin_url"), text(user, "notion_url"), text(user, "email_verified_at"),
                    number(user, "selected_tags_count", 0),
                    Boolean.TRUE.equals(user.get("onboarding_complete")),
                    Boolean.TRUE.equals(user.get("cv_uploaded")),
                    number(user, "experience_years", -1));
        }

        private static String text(Map<String, Object> map, String key) {
            Object value = map.get(key);
            return value == null ? null : String.valueOf(value);
        }

        private static int number(Map<String, Object> map, String key, int fallback) {
            return map.get(key) instanceof Number value ? value.intValue() : fallback;
        }
    }

    public record AuthTokens(String accessToken, String refreshToken, String tokenType) {

        public AuthTokens(String accessToken, String refreshToken) {
            this(accessToken, refreshToken, "bearer");
        }
    }

    public record SessionResponse(boolean verificationRequired, UserResponse user, AuthTokens tokens)
            implements AuthResponse {

        public SessionResponse(UserResponse user, AuthTokens tokens) {
            this(false, user, tokens);
        }
    }

    public record VerificationChallenge(
            boolean verificationRequired, String email, String message, String verificationToken,
            int resendAfterSeconds, String debugCode) implements AuthResponse {
    }
}
