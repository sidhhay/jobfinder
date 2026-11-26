package com.jobfinder.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobfinder.client.Http;
import com.jobfinder.config.AppSettings;
import com.jobfinder.db.Database;
import com.jobfinder.dto.AuthDtos.AuthTokens;
import com.jobfinder.security.InvalidTokenException;
import com.jobfinder.security.Jwt;
import com.jobfinder.security.PasswordHasher;
import com.jobfinder.util.Values;
import com.jobfinder.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Google single sign-on (authorization code flow). Disabled unless client credentials are set. */
@Service
public class GoogleOAuthService {

    private static final String AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String USERINFO_URL = "https://openidconnect.googleapis.com/v1/userinfo";

    private final AppSettings settings;
    private final Http http;
    private final ObjectMapper mapper;
    private final Database db;
    private final PasswordHasher passwords;
    private final SessionService sessions;
    private final SecureRandom random = new SecureRandom();
    private final ConcurrentMap<String, Long> issuedStates = new ConcurrentHashMap<>();

    public GoogleOAuthService(AppSettings settings, Http http, ObjectMapper mapper, Database db,
                              PasswordHasher passwords, SessionService sessions) {
        this.settings = settings;
        this.http = http;
        this.mapper = mapper;
        this.db = db;
        this.passwords = passwords;
        this.sessions = sessions;
    }

    /** @return the Google consent screen address */
    public URI startUrl() {
        requireConfiguration();
        long now = Instant.now().getEpochSecond();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("type", "google_oauth_state");
        String nonce = randomText(24);
        claims.put("nonce", nonce);
        issuedStates.put(nonce, now + 600);
        issuedStates.entrySet().removeIf(entry -> entry.getValue() < now);
        claims.put("iat", now);
        claims.put("exp", now + 600);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("client_id", settings.googleClientId());
        params.put("redirect_uri", settings.googleRedirectUri());
        params.put("response_type", "code");
        params.put("scope", "openid email profile");
        params.put("state", Jwt.encode(claims, settings.jwtSecretKey(), settings.jwtAlgorithm(), null));
        params.put("access_type", "offline");
        params.put("prompt", "select_account");
        return URI.create(AUTH_URL + "?" + Http.query(params));
    }

    /** @return the frontend address to redirect to, carrying either tokens or an error message */
    public URI callback(String code, String state, String error) {
        if (!Values.blank(error)) {
            return failure("Google sign-in failed: " + error);
        }
        if (Values.blank(code) || Values.blank(state)) {
            return failure("Google sign-in failed: missing code or state");
        }
        try {
            requireConfiguration();
            validateState(state);
            Map<String, Object> form = new LinkedHashMap<>();
            form.put("code", code);
            form.put("client_id", settings.googleClientId());
            form.put("client_secret", settings.googleClientSecret());
            form.put("redirect_uri", settings.googleRedirectUri());
            form.put("grant_type", "authorization_code");
            JsonNode tokenResponse = call(HttpRequest.newBuilder(URI.create(TOKEN_URL))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(Http.query(form))), "Google token exchange failed");
            String accessToken = tokenResponse.path("access_token").asText("");
            if (accessToken.isEmpty()) {
                return failure("Google sign-in failed: missing access token");
            }

            JsonNode profile = call(HttpRequest.newBuilder(URI.create(USERINFO_URL))
                    .header("Authorization", "Bearer " + accessToken).GET(), "Google profile fetch failed");
            String email = profile.path("email").asText("").strip().toLowerCase(Locale.ROOT);
            String name = profile.path("name").asText("").strip();
            if (email.isEmpty() || !profile.path("email_verified").asBoolean(false)) {
                return failure("Google account email is not verified");
            }

            AuthTokens tokens = sessions.openSession(findOrCreateUser(email, name.isEmpty() ? "Google User" : name));
            Map<String, Object> query = new LinkedHashMap<>();
            query.put("access_token", tokens.accessToken());
            query.put("refresh_token", tokens.refreshToken());
            // Put credentials in the URL fragment: fragments are not sent in HTTP requests or referrer headers.
            return URI.create(loginPage() + "#" + Http.query(query));
        } catch (ApiException problem) {
            return failure(problem.getMessage());
        }
    }

    private String findOrCreateUser(String email, String fullName) {
        Map<String, Object> user = db.fetchOne("auth/get_user_by_email", email);
        if (user == null) {
            user = db.fetchOne("auth/create_verified_user",
                    email, passwords.hash(randomText(48)), fullName, "", null);
        }
        if (user == null) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to complete Google sign-in");
        }
        return Values.str(user.get("user_id"));
    }

    private JsonNode call(HttpRequest.Builder request, String failureMessage) {
        try {
            HttpResponse<String> response = http.send(request.timeout(Duration.ofSeconds(15)).build());
            if (response.statusCode() >= 400) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, failureMessage + ": " + response.body());
            }
            return mapper.readTree(response.body());
        } catch (IOException error) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, failureMessage, error);
        }
    }

    private void validateState(String state) {
        try {
            Map<String, Object> claims =
                    Jwt.decode(state, settings.jwtSecretKey(), settings.jwtAlgorithm(), true);
            if (!"google_oauth_state".equals(claims.get("type")) || !(claims.get("nonce") instanceof String nonce)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid OAuth state");
            }
            Long expiresAt = issuedStates.remove(nonce);
            if (expiresAt == null || expiresAt < Instant.now().getEpochSecond()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "OAuth state is invalid or already used");
            }
        } catch (InvalidTokenException error) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid OAuth state", error);
        }
    }

    private void requireConfiguration() {
        if (settings.googleClientId() == null || settings.googleClientSecret() == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Google SSO is not configured");
        }
    }

    private String loginPage() {
        return settings.frontendBaseUrl().replaceAll("/+$", "") + "/login";
    }

    private URI failure(String message) {
        return URI.create(loginPage() + "?" + Http.query(Map.of("error", message)));
    }

    private String randomText(int bytes) {
        byte[] buffer = new byte[bytes];
        random.nextBytes(buffer);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer);
    }
}
