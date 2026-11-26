package com.jobfinder.service;

import com.jobfinder.db.Database;
import com.jobfinder.dto.AuthDtos.AuthResponse;
import com.jobfinder.dto.AuthDtos.AuthTokens;
import com.jobfinder.dto.AuthDtos.LoginRequest;
import com.jobfinder.dto.AuthDtos.RegisterRequest;
import com.jobfinder.dto.AuthDtos.ResendVerificationRequest;
import com.jobfinder.dto.AuthDtos.SessionResponse;
import com.jobfinder.dto.AuthDtos.UserResponse;
import com.jobfinder.dto.AuthDtos.VerificationChallenge;
import com.jobfinder.dto.AuthDtos.VerifyEmailRequest;
import com.jobfinder.security.InvalidTokenException;
import com.jobfinder.security.PasswordHasher;
import com.jobfinder.security.TokenService;
import com.jobfinder.util.Values;
import com.jobfinder.web.ApiException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Registration, login, token rotation and logout. */
@Service
public class AuthService {

    private static final String ALREADY_REGISTERED = "Email already registered. Please login instead.";

    /** Result of the rotation transaction: either a new token or the reason for refusal. */
    private record Rotation(String refreshToken, String failure) {
    }

    private final Database db;
    private final TokenService tokens;
    private final SessionService sessions;
    private final PasswordHasher passwords;
    private final VerificationService verification;
    private final UserAccountService users;

    public AuthService(Database db, TokenService tokens, SessionService sessions, PasswordHasher passwords,
                       VerificationService verification, UserAccountService users) {
        this.db = db;
        this.tokens = tokens;
        this.sessions = sessions;
        this.passwords = passwords;
        this.verification = verification;
        this.users = users;
    }

    public AuthResponse register(RegisterRequest request) {
        String email = request.email().toLowerCase(Locale.ROOT);
        requireUnregistered(email);
        return verification.sendChallenge(email, request.fullName(),
                "We sent a verification code to your email. Enter it to finish creating your account.");
    }

    public AuthResponse login(LoginRequest request) {
        Map<String, Object> user = db.fetchOne("auth/get_user_by_email", request.email());
        if (user == null) {
            throw unauthorized("No account found with that email. Please register instead.");
        }
        if (!passwords.matches(request.password(), Values.str(user.get("password_hash")))) {
            throw unauthorized("Incorrect password.");
        }
        if (user.get("email_verified_at") == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Email not verified");
        }
        String userId = Values.str(user.get("user_id"));
        return new SessionResponse(userResponse(requireProfile(userId)), sessions.openSession(userId));
    }

    /** Rotates the refresh token. Reusing an old token revokes the whole session. */
    public AuthResponse refresh(String refreshToken) {
        Map<String, Object> claims = refreshClaims(refreshToken);
        String userId = Values.str(claims.get("sub"));
        String sessionId = Values.str(claims.get("sid"));
        String tokenId = Values.str(claims.get("jti"));

        // A refusal is returned rather than thrown so that a revocation is committed.
        Rotation rotation = db.inTransaction(() -> rotate(userId, sessionId, tokenId));
        if (rotation.failure() != null) {
            throw unauthorized(rotation.failure());
        }

        Map<String, Object> profile = requireProfile(userId);
        if (profile.get("email_verified_at") == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Email not verified");
        }
        return new SessionResponse(userResponse(profile),
                new AuthTokens(tokens.createAccessToken(userId, sessionId), rotation.refreshToken()));
    }

    private Rotation rotate(String userId, String sessionId, String tokenId) {
        Map<String, Object> session = db.fetchOne("auth/get_refresh_session_for_update", sessionId);
        if (session == null || !userId.equals(Values.str(session.get("user_id")))) {
            return new Rotation(null, "Invalid refresh token");
        }
        if (session.get("revoked_at") != null) {
            return new Rotation(null, "Refresh session revoked");
        }
        OffsetDateTime expiresAt = Values.parseTimestamp(Values.str(session.get("expires_at")));
        if (expiresAt == null || !expiresAt.isAfter(OffsetDateTime.now())) {
            db.fetchOne("auth/revoke_refresh_session", "refresh token expired", sessionId);
            return new Rotation(null, "Refresh token expired");
        }
        if (!tokenId.equals(Values.str(session.get("current_jti")))) {
            db.fetchOne("auth/revoke_refresh_session", "refresh token reuse detected", sessionId);
            return new Rotation(null, "Refresh token reuse detected. Please login again.");
        }
        TokenService.RefreshToken next = tokens.buildRefreshToken(userId, sessionId);
        if (db.fetchOne("auth/rotate_refresh_session", next.jti(), next.expiresAt(), sessionId) == null) {
            return new Rotation(null, "Refresh session rotation failed");
        }
        return new Rotation(next.token(), null);
    }

    public SessionResponse verifyEmail(VerifyEmailRequest request) {
        requireUnregistered(request.email());
        verification.verifyCode(request.email(), request.code(), request.verificationToken());
        Map<String, Object> created;
        try {
            created = db.fetchOne("auth/create_verified_user",
                    request.email().toLowerCase(Locale.ROOT),
                    passwords.hash(request.password()),
                    request.fullName(),
                    Objects.requireNonNullElse(request.rawJobTitle(), ""),
                    request.bio());
        } catch (DataIntegrityViolationException race) {
            requireUnregistered(request.email().toLowerCase(Locale.ROOT));
            throw race;
        }
        if (created == null) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to verify email");
        }
        String userId = Values.str(created.get("user_id"));
        return new SessionResponse(userResponse(requireProfile(userId)), sessions.openSession(userId));
    }

    public VerificationChallenge resendVerification(ResendVerificationRequest request) {
        String email = request.email().toLowerCase(Locale.ROOT);
        requireUnregistered(email);
        verification.requireTokenForEmail(email, request.verificationToken());
        return verification.sendChallenge(email, request.fullName(), "A new verification code has been sent.");
    }

    public Map<String, String> logout(String refreshToken) {
        String sessionId = Values.str(refreshClaims(refreshToken).get("sid"));
        db.fetchOne("auth/revoke_refresh_session", "logout", sessionId);
        return Map.of("detail", "Logged out");
    }

    private Map<String, Object> refreshClaims(String token) {
        try {
            return tokens.decodeRefreshToken(token);
        } catch (InvalidTokenException error) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid refresh token", error);
        }
    }

    private void requireUnregistered(String email) {
        if (db.fetchOne("auth/get_user_by_email", email) != null) {
            throw new ApiException(HttpStatus.CONFLICT, ALREADY_REGISTERED);
        }
    }

    private Map<String, Object> requireProfile(String userId) {
        Map<String, Object> profile = users.findProfile(userId);
        if (profile == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "User not found");
        }
        return profile;
    }

    /** Session responses do not report experience years (the client reads it from /auth/session). */
    private static UserResponse userResponse(Map<String, Object> profile) {
        Map<String, Object> copy = new LinkedHashMap<>(profile);
        copy.remove("experience_years");
        return UserResponse.from(copy);
    }

    private static ApiException unauthorized(String detail) {
        return new ApiException(HttpStatus.UNAUTHORIZED, detail);
    }
}
