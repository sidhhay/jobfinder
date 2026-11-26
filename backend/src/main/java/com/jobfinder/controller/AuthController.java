package com.jobfinder.controller;

import com.jobfinder.config.AppSettings;
import com.jobfinder.dto.AuthDtos.AuthResponse;
import com.jobfinder.dto.AuthDtos.AuthTokens;
import com.jobfinder.dto.AuthDtos.LoginRequest;
import com.jobfinder.dto.AuthDtos.RefreshRequest;
import com.jobfinder.dto.AuthDtos.RegisterRequest;
import com.jobfinder.dto.AuthDtos.ResendVerificationRequest;
import com.jobfinder.dto.AuthDtos.SessionResponse;
import com.jobfinder.dto.AuthDtos.UserResponse;
import com.jobfinder.dto.AuthDtos.VerifyEmailRequest;
import com.jobfinder.security.CurrentUser;
import com.jobfinder.security.RateLimit;
import com.jobfinder.service.AuthService;
import com.jobfinder.service.GoogleOAuthService;
import com.jobfinder.service.UserAccountService;
import com.jobfinder.web.ApiException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/** Authentication routes. */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService auth;
    private final GoogleOAuthService google;
    private final UserAccountService users;
    private final AppSettings settings;

    public AuthController(AuthService auth, GoogleOAuthService google, UserAccountService users,
                          AppSettings settings) {
        this.auth = auth;
        this.google = google;
        this.users = users;
        this.settings = settings;
    }

    @RateLimit(perMinute = 3)
    @PostMapping("/register")
    public AuthResponse register(@Valid @RequestBody RegisterRequest request) {
        return auth.register(request);
    }

    @RateLimit(perMinute = 5)
    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return auth.login(request);
    }

    @PostMapping("/refresh")
    public AuthResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return auth.refresh(request.refreshToken());
    }

    @RateLimit(perMinute = 5)
    @PostMapping("/verify-email")
    public AuthResponse verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
        return auth.verifyEmail(request);
    }

    @RateLimit(perMinute = 2)
    @PostMapping("/resend-verification")
    public AuthResponse resendVerification(@Valid @RequestBody ResendVerificationRequest request) {
        return auth.resendVerification(request);
    }

    @PostMapping("/logout")
    public Map<String, String> logout(@Valid @RequestBody RefreshRequest request) {
        return auth.logout(request.refreshToken());
    }

    @GetMapping("/me")
    public UserResponse me(CurrentUser user) {
        return UserResponse.from(user.profile());
    }

    @PostMapping("/session")
    public UserResponse session(CurrentUser user) {
        return UserResponse.from(user.profile());
    }

    /** Development only: returns a ready session for the local user when DEV_AUTO_LOGIN is on. */
    @GetMapping("/dev-session")
    public SessionResponse devSession() {
        if (!settings.devAutoLogin()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Not found");
        }
        Map<String, Object> user = users.ensureDevUser();
        return new SessionResponse(UserResponse.from(user), new AuthTokens("dev-token", "dev-token"));
    }

    @GetMapping("/google/start")
    public ResponseEntity<Void> googleStart() {
        URI target = google.startUrl();
        String state = java.util.Arrays.stream(target.getRawQuery().split("&"))
                .filter(part -> part.startsWith("state="))
                .map(part -> URLDecoder.decode(part.substring("state=".length()), StandardCharsets.UTF_8))
                .findFirst().orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "OAuth state missing"));
        var cookie = org.springframework.http.ResponseCookie.from("jobfinder_oauth_state", state)
                .httpOnly(true)
                .secure(settings.googleRedirectUri().startsWith("https://"))
                .sameSite("Lax")
                .path("/api/auth/google")
                .maxAge(Duration.ofMinutes(10))
                .build();
        return ResponseEntity.status(HttpStatus.FOUND).location(target).header("Set-Cookie", cookie.toString()).build();
    }

    @GetMapping("/google/callback")
    public ResponseEntity<Void> googleCallback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error,
            @CookieValue(name = "jobfinder_oauth_state", required = false) String browserState) {
        if (state == null || browserState == null
                || !java.security.MessageDigest.isEqual(state.getBytes(StandardCharsets.UTF_8),
                        browserState.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "OAuth state does not match this browser session");
        }
        var clearCookie = org.springframework.http.ResponseCookie.from("jobfinder_oauth_state", "")
                .httpOnly(true).secure(settings.googleRedirectUri().startsWith("https://"))
                .sameSite("Lax").path("/api/auth/google").maxAge(Duration.ZERO).build();
        return ResponseEntity.status(HttpStatus.FOUND).location(google.callback(code, state, error))
                .header("Set-Cookie", clearCookie.toString()).build();
    }

    private static ResponseEntity<Void> redirect(URI target) {
        return ResponseEntity.status(HttpStatus.FOUND).location(target).build();
    }
}
