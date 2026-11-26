package com.jobfinder.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** Minimal HMAC (HS256/384/512) JSON Web Token encoder and verifier. */
public final class Jwt {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private static final Map<String, String> ALGORITHMS =
            Map.of("HS256", "HmacSHA256", "HS384", "HmacSHA384", "HS512", "HmacSHA512");

    private Jwt() {
    }

    public static String encode(Map<String, Object> claims, String secret, String algorithm, String keyId) {
        try {
            Map<String, Object> header = new LinkedHashMap<>();
            header.put("alg", algorithm);
            header.put("typ", "JWT");
            if (keyId != null) {
                header.put("kid", keyId);
            }
            String body = ENCODER.encodeToString(MAPPER.writeValueAsBytes(header)) + "."
                    + ENCODER.encodeToString(MAPPER.writeValueAsBytes(claims));
            return body + "." + ENCODER.encodeToString(sign(body, secret, algorithm));
        } catch (IOException error) {
            throw new IllegalStateException("Unable to serialise token", error);
        }
    }

    /** Reads the header without verifying the signature. */
    public static Map<String, Object> header(String token) {
        return parse(split(token)[0]);
    }

    /** Verifies signature, algorithm and (optionally) expiry, then returns the claims. */
    public static Map<String, Object> decode(String token, String secret, String algorithm, boolean verifyExpiry) {
        String[] parts = split(token);
        if (!algorithm.equals(parse(parts[0]).get("alg"))) {
            throw new InvalidTokenException("Unexpected algorithm");
        }
        byte[] expected = sign(parts[0] + "." + parts[1], secret, algorithm);
        byte[] actual;
        try {
            actual = DECODER.decode(parts[2]);
        } catch (IllegalArgumentException error) {
            throw new InvalidTokenException("Malformed signature");
        }
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new InvalidTokenException("Signature verification failed");
        }
        Map<String, Object> claims = parse(parts[1]);
        Object expiry = claims.get("exp");
        if (verifyExpiry && expiry != null
                && (!(expiry instanceof Number number) || number.longValue() <= Instant.now().getEpochSecond())) {
            throw new InvalidTokenException("Token has expired");
        }
        return claims;
    }

    private static String[] split(String token) {
        String[] parts = token == null ? new String[0] : token.split("\\.", -1);
        if (parts.length != 3) {
            throw new InvalidTokenException("Not a JWT");
        }
        return parts;
    }

    private static Map<String, Object> parse(String segment) {
        try {
            Map<String, Object> value =
                    MAPPER.readValue(DECODER.decode(segment), new TypeReference<Map<String, Object>>() { });
            if (value == null) {
                throw new InvalidTokenException("Empty token segment");
            }
            return value;
        } catch (IOException | IllegalArgumentException error) {
            throw new InvalidTokenException("Malformed token");
        }
    }

    private static byte[] sign(String data, String secret, String algorithm) {
        String javaName = ALGORITHMS.get(algorithm);
        if (javaName == null) {
            throw new IllegalStateException("Unsupported JWT algorithm: " + algorithm);
        }
        try {
            Mac mac = Mac.getInstance(javaName);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), javaName));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("Unable to sign token", error);
        }
    }
}
