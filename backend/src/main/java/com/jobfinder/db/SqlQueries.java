package com.jobfinder.db;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Loads the raw SQL files stored under {@code resources/sql} and caches them. */
@Component
public class SqlQueries {

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    /** @param name path below {@code sql/} without extension, e.g. {@code auth/get_user_by_id} */
    public String get(String name) {
        return cache.computeIfAbsent(name, key -> {
            try (InputStream stream = new ClassPathResource("sql/" + key + ".sql").getInputStream()) {
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException error) {
                throw new UncheckedIOException("Missing SQL file: " + key, error);
            }
        });
    }
}
