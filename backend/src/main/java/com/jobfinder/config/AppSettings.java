package com.jobfinder.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Application configuration. Values come from process environment variables first and
 * then from the {@code .env} file in the backend folder.
 */
@Component
public class AppSettings {

    private final Map<String, String> fileValues = loadEnvFile();

    private static Map<String, String> loadEnvFile() {
        String override = System.getenv("JOBFINDER_ENV_FILE");
        List<Path> candidates = override != null
                ? List.of(Path.of(override))
                : List.of(Path.of(".env"), Path.of("backend", ".env"));
        Map<String, String> values = new HashMap<>();
        for (Path path : candidates) {
            if (!Files.isRegularFile(path)) {
                continue;
            }
            try {
                for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                    parseLine(line, values);
                }
            } catch (IOException error) {
                throw new IllegalStateException("Unable to read " + path.toAbsolutePath(), error);
            }
            break;
        }
        return values;
    }

    /** Parses one {@code KEY=value} line, honouring quotes and trailing comments. */
    static void parseLine(String line, Map<String, String> target) {
        String trimmed = line.strip();
        int equals = trimmed.indexOf('=');
        if (trimmed.startsWith("#") || equals <= 0) {
            return;
        }
        String key = trimmed.substring(0, equals).strip();
        String value = trimmed.substring(equals + 1).strip();
        boolean quoted = value.length() >= 2
                && (value.charAt(0) == '"' || value.charAt(0) == '\'')
                && value.indexOf(value.charAt(0), 1) > 0;
        if (quoted) {
            value = value.substring(1, value.indexOf(value.charAt(0), 1));
        } else if (value.startsWith("#")) {
            value = "";
        } else {
            value = value.split("\\s+#", 2)[0].strip();
        }
        target.put(key, value);
    }

    /** Returns the configured value, or {@code null} when it is missing or blank. */
    private String get(String key) {
        String value = System.getenv(key);
        if (value == null) {
            value = fileValues.get(key);
        }
        return value == null || value.isBlank() ? null : value;
    }

    private String get(String key, String fallback) {
        String value = get(key);
        return value == null ? fallback : value;
    }

    private int getInt(String key, int fallback) {
        String value = get(key);
        return value == null ? fallback : Integer.parseInt(value.strip());
    }

    private boolean getBool(String key, boolean fallback) {
        String value = get(key);
        if (value == null) {
            return fallback;
        }
        return List.of("true", "1", "yes", "on").contains(value.strip().toLowerCase());
    }

    // ── database ────────────────────────────────────────────────────────────
    public String databaseUrl() { return get("APP_DATABASE_URL", get("DATABASE_URL")); }
    public String databaseSchema() { return get("DB_SCHEMA", "jobful"); }

    // ── CORS ────────────────────────────────────────────────────────────────
    public List<String> allowedOrigins() {
        String raw = get("ALLOWED_ORIGINS", "http://localhost:3000").strip();
        if (raw.startsWith("[")) {
            try {
                return new ObjectMapper().readValue(raw, new TypeReference<List<String>>() { });
            } catch (IOException ignored) {
                // fall through and treat the value as a comma separated list
            }
        }
        return Arrays.stream(raw.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    // ── JWT ─────────────────────────────────────────────────────────────────
    public String jwtSecretKey() { return get("JWT_SECRET_KEY", "dev-only-change-me"); }
    public String jwtAlgorithm() { return get("JWT_ALGORITHM", "HS256"); }
    public String jwtActiveKid() { return get("JWT_ACTIVE_KID", "v1"); }
    public boolean jwtAllowLegacyNoKid() { return getBool("JWT_ALLOW_LEGACY_NO_KID", true); }
    public String jwtIssuer() { return get("JWT_ISSUER", "jobful-api"); }
    public String jwtAudience() { return get("JWT_AUDIENCE", "jobful-client"); }
    public int accessTokenMinutes() { return getInt("ACCESS_TOKEN_MINUTES", 15); }
    public int refreshTokenDays() { return getInt("REFRESH_TOKEN_DAYS", 7); }

    /** Map of key id to signing secret, used for key rotation. */
    public Map<String, String> jwtSigningKeys() {
        String raw = get("JWT_KEYS_JSON");
        if (raw == null) {
            return Map.of(jwtActiveKid(), jwtSecretKey());
        }
        Map<String, String> keys;
        try {
            keys = new ObjectMapper().readValue(raw, new TypeReference<LinkedHashMap<String, String>>() { });
        } catch (IOException error) {
            throw new IllegalStateException("JWT_KEYS_JSON must be valid JSON", error);
        }
        boolean invalid = keys.isEmpty() || keys.entrySet().stream()
                .anyMatch(e -> e.getKey().isBlank() || e.getValue() == null || e.getValue().isBlank());
        if (invalid) {
            throw new IllegalStateException("JWT_KEYS_JSON must be a non-empty JSON object of {kid: secret}");
        }
        if (!keys.containsKey(jwtActiveKid())) {
            throw new IllegalStateException("JWT_ACTIVE_KID must exist in JWT_KEYS_JSON");
        }
        return keys;
    }

    // ── email ───────────────────────────────────────────────────────────────
    public String smtpHost() { return get("SMTP_HOST"); }
    public int smtpPort() { return getInt("SMTP_PORT", 587); }
    public String smtpUsername() { return get("SMTP_USERNAME"); }
    public String smtpPassword() { return get("SMTP_PASSWORD", ""); }
    public String smtpFromEmail() { return get("SMTP_FROM_EMAIL"); }
    public boolean smtpUseTls() { return getBool("SMTP_USE_TLS", true); }
    public int emailCodeMinutes() { return getInt("EMAIL_VERIFICATION_CODE_MINUTES", 10); }
    public int emailResendCooldownSeconds() { return getInt("EMAIL_VERIFICATION_RESEND_COOLDOWN_SECONDS", 60); }
    public boolean authDebugReturnCodes() { return getBool("AUTH_DEBUG_RETURN_CODES", false); }
    public boolean sqlLogEnabled() { return getBool("SQL_LOG_ENABLED", false); }
    public boolean devAutoLogin() { return getBool("DEV_AUTO_LOGIN", false); }

    // ── Google SSO ──────────────────────────────────────────────────────────
    public String frontendBaseUrl() { return get("FRONTEND_BASE_URL", "http://localhost:3000"); }
    public String googleClientId() { return get("GOOGLE_CLIENT_ID"); }
    public String googleClientSecret() { return get("GOOGLE_CLIENT_SECRET"); }
    public String googleRedirectUri() {
        return get("GOOGLE_REDIRECT_URI", "http://localhost:8000/api/auth/google/callback");
    }

    // ── AI ──────────────────────────────────────────────────────────────────
    public String groqApiKey() { return get("GROQ_API_KEY"); }

    /** Model used for CV parsing, job screening and document writing. */
    public String groqModel() { return get("GROQ_MODEL", "openai/gpt-oss-120b"); }

    public String huggingFaceToken() { return get("HF_TOKEN"); }
    public String huggingFaceModel() { return get("HF_MODEL", "mistralai/Mistral-7B-Instruct-v0.3"); }

    // ── CV upload ───────────────────────────────────────────────────────────
    public int cvMaxBytes() { return getInt("CV_MAX_BYTES", 5 * 1024 * 1024); }
    public int cvMinTextChars() { return getInt("CV_MIN_TEXT_CHARS", 200); }

    // ── storage ─────────────────────────────────────────────────────────────
    public String supabaseUrl() { return get("SUPABASE_URL"); }
    public String supabaseServiceRoleKey() { return get("SUPABASE_SERVICE_ROLE_KEY"); }
    public String supabaseStorageBucket() { return get("SUPABASE_STORAGE_BUCKET", "generated-documents"); }

    // ── job search ──────────────────────────────────────────────────────────
    public String rapidApiKey() { return get("RAPIDAPI_KEY"); }
    public String adzunaAppId() { return get("ADZUNA_APP_ID"); }
    public String adzunaAppKey() { return get("ADZUNA_APP_KEY"); }
    public int jobSearchPages() { return getInt("JOB_SEARCH_PAGES", 2); }
    public double jobSearchDelaySeconds() { return Double.parseDouble(get("JOB_SEARCH_DELAY", "0.5")); }
}
