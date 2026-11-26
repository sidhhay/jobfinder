package com.jobfinder.security;

import com.jobfinder.config.AppSettings;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Issues and validates the application's access and refresh tokens. */
@Service
public class TokenService {

    /** A freshly built refresh token and the values stored for rotation. */
    public record RefreshToken(String token, String jti, OffsetDateTime expiresAt) {
    }

    private final AppSettings settings;

    public TokenService(AppSettings settings) {
        this.settings = settings;
        settings.jwtSigningKeys(); // fail fast on bad key configuration
    }

    public String createAccessToken(String userId, String sessionId) {
        Map<String, Object> extra = new LinkedHashMap<>();
        if (sessionId != null) {
            extra.put("sid", sessionId);
        }
        return createToken(userId, "access", Duration.ofMinutes(settings.accessTokenMinutes()), extra);
    }

    public RefreshToken buildRefreshToken(String userId, String sessionId) {
        Duration lifetime = Duration.ofDays(settings.refreshTokenDays());
        String jti = UUID.randomUUID().toString();
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("sid", sessionId);
        extra.put("jti", jti);
        String token = createToken(userId, "refresh", lifetime, extra);
        return new RefreshToken(token, jti, OffsetDateTime.now(ZoneOffset.UTC).plus(lifetime));
    }

    private String createToken(String subject, String type, Duration lifetime, Map<String, Object> extra) {
        long now = OffsetDateTime.now(ZoneOffset.UTC).toEpochSecond();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", subject);
        claims.put("type", type);
        claims.put("iat", now);
        claims.put("exp", now + lifetime.toSeconds());
        claims.put("iss", settings.jwtIssuer());
        claims.put("aud", settings.jwtAudience());
        claims.putAll(extra);
        String kid = settings.jwtActiveKid();
        return Jwt.encode(claims, settings.jwtSigningKeys().get(kid), settings.jwtAlgorithm(), kid);
    }

    /** Verifies signature, expiry, issuer, audience and the required claims. */
    public Map<String, Object> decode(String token) {
        Map<String, Object> claims = Jwt.decode(token, signingKeyFor(token), settings.jwtAlgorithm(), true);
        for (String required : List.of("exp", "iat", "sub", "type", "iss", "aud")) {
            if (claims.get(required) == null) {
                throw new InvalidTokenException("Missing claim " + required);
            }
        }
        if (!settings.jwtIssuer().equals(claims.get("iss"))) {
            throw new InvalidTokenException("Invalid issuer");
        }
        Object audience = claims.get("aud");
        boolean audienceMatches = audience instanceof List<?> list
                ? list.contains(settings.jwtAudience())
                : settings.jwtAudience().equals(audience);
        if (!audienceMatches) {
            throw new InvalidTokenException("Invalid audience");
        }
        return claims;
    }

    /** Decodes a refresh token and requires the session and token ids. */
    public Map<String, Object> decodeRefreshToken(String token) {
        Map<String, Object> claims = decode(token);
        if (!"refresh".equals(claims.get("type")) || claims.get("sid") == null || claims.get("jti") == null) {
            throw new InvalidTokenException("Not a refresh token");
        }
        return claims;
    }

    private String signingKeyFor(String token) {
        Object kid = Jwt.header(token).get("kid");
        if (kid != null) {
            String key = settings.jwtSigningKeys().get(String.valueOf(kid));
            if (key == null) {
                throw new InvalidTokenException("Unknown token key id");
            }
            return key;
        }
        if (settings.jwtAllowLegacyNoKid()) {
            return settings.jwtSecretKey();
        }
        throw new InvalidTokenException("Token key id missing");
    }
}
