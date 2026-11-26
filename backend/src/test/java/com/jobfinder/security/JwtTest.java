package com.jobfinder.security;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtTest {

    private static final String SECRET = "unit-test-secret-that-is-long-enough";

    private static String token(long expiresIn) {
        Map<String, Object> claims = Map.of("sub", "user-1", "exp", Instant.now().getEpochSecond() + expiresIn);
        return Jwt.encode(claims, SECRET, "HS256", "v1");
    }

    @Test
    void roundTripKeepsClaimsAndKeyId() {
        String token = token(60);
        assertEquals("user-1", Jwt.decode(token, SECRET, "HS256", true).get("sub"));
        assertEquals("v1", Jwt.header(token).get("kid"));
    }

    @Test
    void rejectsWrongSecretTamperingAndExpiry() {
        String token = token(60);
        assertThrows(InvalidTokenException.class, () -> Jwt.decode(token, "another-secret", "HS256", true));
        assertThrows(InvalidTokenException.class, () -> Jwt.decode(token + "x", SECRET, "HS256", true));
        assertThrows(InvalidTokenException.class, () -> Jwt.decode("not.a.token", SECRET, "HS256", true));
        assertThrows(InvalidTokenException.class, () -> Jwt.decode(token(-1), SECRET, "HS256", true));
    }

    @Test
    void passwordHashesVerify() {
        PasswordHasher hasher = new PasswordHasher();
        String hash = hasher.hash("password123");
        assertEquals(true, hasher.matches("password123", hash));
        assertEquals(false, hasher.matches("password124", hash));
        assertEquals(false, hasher.matches("password123", "$dev-bypass$"));
    }
}
