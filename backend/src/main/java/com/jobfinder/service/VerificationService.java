package com.jobfinder.service;

import com.jobfinder.config.AppSettings;
import com.jobfinder.dto.AuthDtos.VerificationChallenge;
import com.jobfinder.security.InvalidTokenException;
import com.jobfinder.security.Jwt;
import com.jobfinder.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Stateless email verification. The salted hash of the six digit code travels inside a signed
 * token, so nothing is stored until the code is confirmed.
 */
@Service
public class VerificationService {

    private final AppSettings settings;
    private final EmailService emails;
    private final SecureRandom random = new SecureRandom();

    public VerificationService(AppSettings settings, EmailService emails) {
        this.settings = settings;
        this.emails = emails;
    }

    /** Emails a new code and returns the challenge the client must answer. */
    public VerificationChallenge sendChallenge(String email, String fullName, String message) {
        String code = String.format("%06d", random.nextInt(1_000_000));
        byte[] saltBytes = new byte[16];
        random.nextBytes(saltBytes);
        String salt = Base64.getUrlEncoder().withoutPadding().encodeToString(saltBytes);

        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("type", "email_verification");
        claims.put("email", email);
        claims.put("code_hash", hash(code, salt));
        claims.put("salt", salt);
        claims.put("exp", Instant.now().getEpochSecond() + settings.emailCodeMinutes() * 60L);
        String token = Jwt.encode(claims, settings.jwtSecretKey(), settings.jwtAlgorithm(), null);

        emails.sendVerificationEmail(email, fullName, code);
        boolean exposeCode = settings.authDebugReturnCodes() || settings.smtpHost() == null;
        return new VerificationChallenge(true, email, message, token,
                settings.emailResendCooldownSeconds(), exposeCode ? code : null);
    }

    /** Checks that an earlier (possibly expired) token belongs to this address. */
    public void requireTokenForEmail(String email, String token) {
        claimsFor(email, token, false, "Verification token invalid");
    }

    public void verifyCode(String email, String code, String token) {
        Map<String, Object> claims = claimsFor(email, token, true, "Verification code expired or invalid");
        byte[] expected = String.valueOf(claims.getOrDefault("code_hash", "")).getBytes(StandardCharsets.UTF_8);
        byte[] candidate = hash(code, String.valueOf(claims.getOrDefault("salt", "")))
                .getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, candidate)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid verification code");
        }
    }

    private Map<String, Object> claimsFor(String email, String token, boolean checkExpiry, String failure) {
        Map<String, Object> claims;
        try {
            claims = Jwt.decode(token, settings.jwtSecretKey(), settings.jwtAlgorithm(), checkExpiry);
        } catch (InvalidTokenException error) {
            throw new ApiException(HttpStatus.BAD_REQUEST, failure, error);
        }
        if (!"email_verification".equals(claims.get("type"))
                || !String.valueOf(claims.getOrDefault("email", "")).equalsIgnoreCase(email)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, failure);
        }
        return claims;
    }

    private String hash(String code, String salt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String material = settings.jwtSecretKey() + ":" + salt + ":" + code;
            return HexFormat.of().formatHex(digest.digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is not available", error);
        }
    }
}
