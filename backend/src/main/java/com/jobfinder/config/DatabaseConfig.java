package com.jobfinder.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/** Builds the pooled PostgreSQL data source from a {@code postgres://} connection URL. */
@Configuration
public class DatabaseConfig {

    @Bean
    public DataSource dataSource(AppSettings settings) {
        String url = settings.databaseUrl();
        if (url == null) {
            throw new IllegalStateException("APP_DATABASE_URL or DATABASE_URL is not configured");
        }
        String schema = settings.databaseSchema();
        if (!schema.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalStateException("DB_SCHEMA must be a plain identifier");
        }

        URI uri = URI.create(url.strip());
        // stringtype=unspecified lets PostgreSQL infer uuid, jsonb and date types from text parameters.
        // prepareThreshold=0 keeps the driver compatible with transaction poolers.
        String query = "stringtype=unspecified&prepareThreshold=0"
                + (uri.getRawQuery() == null ? "" : "&" + uri.getRawQuery());
        int port = uri.getPort() == -1 ? 5432 : uri.getPort();

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:postgresql://" + uri.getHost() + ":" + port + uri.getRawPath() + "?" + query);
        String userInfo = uri.getRawUserInfo();
        if (userInfo != null) {
            String[] parts = userInfo.split(":", 2);
            config.setUsername(decode(parts[0]));
            if (parts.length > 1) {
                config.setPassword(decode(parts[1]));
            }
        }
        config.setConnectionInitSql("SET search_path TO " + schema + ", public");
        config.setMaximumPoolSize(10);
        config.setConnectionTimeout(10_000);
        // Start even if the database is down; requests then answer with HTTP 503.
        config.setInitializationFailTimeout(-1);
        return new HikariDataSource(config);
    }

    private static String decode(String value) {
        return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
    }
}
