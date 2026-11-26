package com.jobfinder.security;

import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/** BCrypt password hashing, compatible with hashes created by the previous backend. */
@Component
public class PasswordHasher {

    public String hash(String password) {
        return BCrypt.hashpw(limit(password), BCrypt.gensalt(12));
    }

    public boolean matches(String password, String hash) {
        if (hash == null || !hash.startsWith("$2")) {
            return false;
        }
        return BCrypt.checkpw(limit(password), hash);
    }

    /** BCrypt only uses the first 72 bytes of a password. */
    private static String limit(String password) {
        String value = password;
        while (value.getBytes(StandardCharsets.UTF_8).length > 72) {
            value = value.substring(0, value.offsetByCodePoints(value.length(), -1));
        }
        return value;
    }
}
