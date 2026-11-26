package com.jobfinder.util;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Small null-safe conversions for loosely typed data (database rows and JSON). */
public final class Values {

    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern SPACES = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    private Values() {
    }

    /** String form of a value; {@code null} becomes the empty string. */
    public static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    public static boolean blank(String value) {
        return value == null || value.isEmpty();
    }

    public static String firstNonBlank(String... values) {
        for (String value : values) {
            if (!blank(value)) {
                return value;
            }
        }
        return "";
    }

    public static String nullIfEmpty(String value) {
        return blank(value) ? null : value;
    }

    /** Collapses all whitespace runs to single spaces. */
    public static String normalizeSpace(Object value) {
        return SPACES.matcher(str(value)).replaceAll(" ").strip();
    }

    /** Removes HTML tags and collapses whitespace. */
    public static String clean(Object value) {
        return normalizeSpace(HTML_TAG.matcher(str(value)).replaceAll(" "));
    }

    public static String truncate(String value, int length) {
        return value.length() <= length ? value : value.substring(0, length);
    }

    public static List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().filter(Objects::nonNull).map(String::valueOf).toList();
        }
        return List.of();
    }

    /** Parses ISO timestamps with or without offset; {@code null} when unparseable. */
    public static OffsetDateTime parseTimestamp(String text) {
        if (blank(text)) {
            return null;
        }
        String value = text.strip().replace(' ', 'T');
        try {
            return OffsetDateTime.parse(value);
        } catch (DateTimeParseException ignored) {
            // try the format without an offset
        }
        try {
            return LocalDateTime.parse(value).atOffset(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
            // try a plain date
        }
        try {
            return LocalDate.parse(value).atStartOfDay().atOffset(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }
}
