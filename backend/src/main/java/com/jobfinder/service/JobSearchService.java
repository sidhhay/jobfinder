package com.jobfinder.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobfinder.client.Http;
import com.jobfinder.config.AppSettings;
import com.jobfinder.util.Values;
import com.jobfinder.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Live job search across JSearch, Adzuna and three keyless remote-job boards (Himalayas, Remotive, Jobicy).
 * Each provider fails independently, so one broken key never empties the whole search.
 * Results are never written to the database.
 */
@Service
public class JobSearchService {

    /** Search options chosen by the user. */
    public record Query(
            String text, String location, String country, String source, String employmentType,
            String workModel, String datePosted, int salaryMin, boolean allowUnspecifiedPay) {
    }

    /** Jobs found plus non-fatal provider problems the UI can show. */
    public record Outcome(List<Map<String, Object>> jobs, List<String> warnings) {
    }

    private static final Logger LOG = LoggerFactory.getLogger(JobSearchService.class);

    /** Country code to the words remote boards use in their location fields. */
    private static final Map<String, List<String>> COUNTRY_WORDS = Map.of(
            "in", List.of("india", "apac", "asia"),
            "us", List.of("usa", "united states", "americas", "north america"),
            "gb", List.of("uk", "united kingdom", "europe", "emea"),
            "au", List.of("australia", "apac"),
            "ca", List.of("canada", "americas", "north america"),
            "de", List.of("germany", "europe", "emea"),
            "sg", List.of("singapore", "apac", "asia"),
            "ae", List.of("uae", "united arab emirates", "emea", "middle east"));

    private static final Map<String, String> COUNTRY_NAMES = Map.of(
            "in", "India", "us", "United States", "gb", "United Kingdom", "au", "Australia",
            "ca", "Canada", "de", "Germany", "sg", "Singapore", "ae", "United Arab Emirates");

    private static final List<String> ANYWHERE = List.of("worldwide", "anywhere", "global");

    private final AppSettings settings;
    private final Http http;
    private final ObjectMapper mapper;

    public JobSearchService(AppSettings settings, Http http, ObjectMapper mapper) {
        this.settings = settings;
        this.http = http;
        this.mapper = mapper;
    }

    public Outcome search(Query query) {
        List<Map<String, Object>> found = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int attempted = 0;
        int failed = 0;
        String source = query.source();
        boolean all = source.equals("all");
        if ((all || source.equals("jsearch")) && settings.rapidApiKey() != null) {
            attempted++;
            failed += collect("JSearch", () -> fetchJSearch(query), found, warnings);
        }
        if ((all || source.equals("adzuna")) && settings.adzunaAppId() != null && settings.adzunaAppKey() != null) {
            attempted++;
            failed += collect("Adzuna", () -> fetchAdzuna(query), found, warnings);
        }
        if (all || source.equals("himalayas")) {
            attempted++;
            failed += collect("Himalayas", () -> fetchHimalayas(query), found, warnings);
        }
        if (all || source.equals("remotive")) {
            attempted++;
            failed += collect("Remotive", () -> fetchRemotive(query), found, warnings);
        }
        if (all || source.equals("jobicy")) {
            attempted++;
            failed += collect("Jobicy", () -> fetchJobicy(query), found, warnings);
        }
        if (attempted > 0 && failed == attempted) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "All job sources failed: " + String.join("; ", warnings));
        }
        List<Map<String, Object>> jobs = dedupe(found).stream()
                .filter(job -> relevant(Values.str(job.get("title")), query.text()))
                .toList();
        if (query.salaryMin() > 0) {
            jobs = jobs.stream()
                    .filter(job -> job.get("salary_min_raw") instanceof Double pay
                            ? pay >= query.salaryMin()
                            : query.allowUnspecifiedPay())
                    .toList();
        }
        return new Outcome(jobs, warnings);
    }

    /** Runs one provider; returns 1 when it failed. */
    private static int collect(String name, Supplier<List<Map<String, Object>>> provider,
                               List<Map<String, Object>> found, List<String> warnings) {
        try {
            found.addAll(provider.get());
            return 0;
        } catch (RuntimeException error) {
            LOG.warn("{} search failed: {}", name, error.getMessage());
            warnings.add(name + " unavailable (" + Values.truncate(error.getMessage(), 160) + ")");
            return 1;
        }
    }

    /** Remote boards list where a candidate may live; keep jobs open to the chosen country. */
    static boolean openTo(String candidateLocation, String country) {
        String where = candidateLocation.toLowerCase(Locale.ROOT);
        if (where.isBlank() || ANYWHERE.stream().anyMatch(where::contains)) {
            return true;
        }
        return COUNTRY_WORDS.getOrDefault(country, List.of(country)).stream().anyMatch(where::contains);
    }

    /**
     * Keeps a job only when its title matches the searched tag, e.g. "data-engineering" matches
     * "Senior Data Engineer" and "fullstack" matches "Full-Stack Developer". Keyless boards
     * return loose matches, so without this a "backend" search shows writers and sales roles.
     */
    static boolean relevant(String title, String query) {
        String compactTitle = title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        String compactQuery = query.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (compactQuery.isEmpty() || compactTitle.contains(compactQuery)) {
            return true;
        }
        List<String> tokens = java.util.Arrays.stream(query.toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
                .filter(token -> token.length() > 2).toList();
        if (tokens.isEmpty()) {
            return true;
        }
        // "engineering" → "engine" also matches "engineer"; "development" → "develo" matches "developer"
        return tokens.stream().allMatch(token -> compactTitle.contains(token.substring(0, Math.min(6, token.length()))));
    }

    private List<Map<String, Object>> fetchHimalayas(Query query) {
        String fetchedAt = OffsetDateTime.now(ZoneOffset.UTC).toString();
        String country = query.country().toLowerCase(Locale.ROOT);
        List<Map<String, Object>> jobs = new ArrayList<>();
        int pages = Math.max(1, settings.jobSearchPages());
        for (int page = 1; page <= pages; page++) {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("q", query.text());
            params.put("limit", 20);
            params.put("page", page);
            String countryName = COUNTRY_NAMES.get(country);
            if (countryName != null) {
                params.put("country", countryName);
            }
            JsonNode payload = getJson("https://himalayas.app/jobs/api/search?" + Http.query(params), Map.of());
            JsonNode items = payload.path("jobs");
            for (JsonNode item : items) {
                List<String> restrictions = new ArrayList<>();
                item.path("locationRestrictions").forEach(node -> restrictions.add(node.asText("")));
                String where = String.join(", ", restrictions);
                Double minimum = number(item, "minSalary");
                Double maximum = number(item, "maxSalary");
                long published = item.path("pubDate").asLong(0);
                Map<String, Object> job = new LinkedHashMap<>();
                job.put("source", "himalayas");
                job.put("external_id", Values.firstNonBlank(text(item, "guid"), text(item, "applicationLink")));
                job.put("title", text(item, "title"));
                job.put("company", text(item, "companyName"));
                job.put("location", where.isEmpty() ? "Remote · Worldwide" : "Remote · " + where);
                job.put("country", country);
                job.put("description", stripHtml(Values.firstNonBlank(text(item, "description"),
                        text(item, "excerpt"))));
                job.put("apply_url", text(item, "applicationLink"));
                job.put("source_url", text(item, "applicationLink"));
                job.put("salary_range", salaryRange(minimum, maximum, text(item, "currency")));
                job.put("salary_min_raw", minimum);
                job.put("employment_type", text(item, "employmentType").replaceAll("[^A-Za-z]", "")
                        .toUpperCase(Locale.ROOT));
                job.put("work_model", "Remote");
                job.put("posted_at", published > 0
                        ? java.time.Instant.ofEpochSecond(published).atOffset(ZoneOffset.UTC).toString() : "");
                job.put("fetched_at", fetchedAt);
                jobs.add(job);
            }
            if (items.size() < 20) {
                break;
            }
            pause(page, pages);
        }
        return jobs;
    }

    private List<Map<String, Object>> fetchRemotive(Query query) {
        String fetchedAt = OffsetDateTime.now(ZoneOffset.UTC).toString();
        String country = query.country().toLowerCase(Locale.ROOT);
        JsonNode payload = getJson("https://remotive.com/api/remote-jobs?"
                + Http.query(Map.of("search", query.text(), "limit", 100)), Map.of());
        List<Map<String, Object>> jobs = new ArrayList<>();
        for (JsonNode item : payload.path("jobs")) {
            String where = text(item, "candidate_required_location");
            if (!openTo(where, country)) {
                continue;
            }
            Map<String, Object> job = new LinkedHashMap<>();
            job.put("source", "remotive");
            job.put("external_id", text(item, "id"));
            job.put("title", text(item, "title"));
            job.put("company", text(item, "company_name"));
            job.put("location", where.isEmpty() ? "Remote" : "Remote · " + where);
            job.put("country", country);
            job.put("description", stripHtml(text(item, "description")));
            job.put("apply_url", text(item, "url"));
            job.put("source_url", text(item, "url"));
            String salary = text(item, "salary");
            job.put("salary_range", salary.isEmpty() ? null : salary);
            job.put("salary_min_raw", null);
            job.put("employment_type", text(item, "job_type").replace("_", "").toUpperCase(Locale.ROOT));
            job.put("work_model", "Remote");
            job.put("posted_at", utc(text(item, "publication_date")));
            job.put("fetched_at", fetchedAt);
            jobs.add(job);
        }
        return jobs;
    }

    private List<Map<String, Object>> fetchJobicy(Query query) {
        String fetchedAt = OffsetDateTime.now(ZoneOffset.UTC).toString();
        String country = query.country().toLowerCase(Locale.ROOT);
        String tag = query.text().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        JsonNode payload = getJson("https://jobicy.com/api/v2/remote-jobs?"
                + Http.query(Map.of("count", 50, "tag", tag)), Map.of());
        List<Map<String, Object>> jobs = new ArrayList<>();
        for (JsonNode item : payload.path("jobs")) {
            String where = text(item, "jobGeo");
            if (!openTo(where, country)) {
                continue;
            }
            Double minimum = number(item, "annualSalaryMin");
            Double maximum = number(item, "annualSalaryMax");
            JsonNode types = item.path("jobType");
            String type = types.isArray() && !types.isEmpty() ? types.get(0).asText("") : text(item, "jobType");
            Map<String, Object> job = new LinkedHashMap<>();
            job.put("source", "jobicy");
            job.put("external_id", text(item, "id"));
            job.put("title", text(item, "jobTitle"));
            job.put("company", text(item, "companyName"));
            job.put("location", where.isEmpty() ? "Remote" : "Remote · " + where);
            job.put("country", country);
            job.put("description", stripHtml(Values.firstNonBlank(text(item, "jobDescription"),
                    text(item, "jobExcerpt"))));
            job.put("apply_url", text(item, "url"));
            job.put("source_url", text(item, "url"));
            job.put("salary_range", salaryRange(minimum, maximum, text(item, "salaryCurrency")));
            job.put("salary_min_raw", minimum);
            job.put("employment_type", type.replaceAll("[^A-Za-z]", "").toUpperCase(Locale.ROOT));
            job.put("work_model", "Remote");
            job.put("posted_at", utc(text(item, "pubDate")));
            job.put("fetched_at", fetchedAt);
            jobs.add(job);
        }
        return jobs;
    }

    private static String stripHtml(String html) {
        return Values.normalizeSpace(html.replaceAll("(?s)<[^>]+>", " ")
                .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&#39;", "'").replace("&quot;", "\""));
    }

    /** Remotive dates have no zone; treat them as UTC so they parse and sort consistently. */
    private static String utc(String timestamp) {
        if (timestamp.isEmpty() || timestamp.endsWith("Z") || timestamp.matches(".*[+-]\\d{2}:\\d{2}$")) {
            return timestamp;
        }
        return timestamp + "Z";
    }

    private List<Map<String, Object>> fetchJSearch(Query query) {
        String key = settings.rapidApiKey();
        if (key == null) {
            return List.of();
        }
        String fetchedAt = OffsetDateTime.now(ZoneOffset.UTC).toString();
        String country = query.country().toLowerCase(Locale.ROOT);
        List<Map<String, Object>> jobs = new ArrayList<>();
        int pages = settings.jobSearchPages();
        for (int page = 1; page <= pages; page++) {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("query", query.location().isEmpty()
                    ? query.text() : query.text() + " in " + query.location());
            params.put("page", page);
            params.put("num_pages", 1);
            params.put("country", country);
            params.put("date_posted", query.datePosted().isEmpty() ? "month" : query.datePosted());
            if (!query.employmentType().isEmpty()) {
                params.put("employment_types", query.employmentType());
            }
            if (query.workModel().equalsIgnoreCase("remote")) {
                params.put("work_from_home", "true");
            }
            JsonNode payload = getJson("https://jsearch.p.rapidapi.com/search?" + Http.query(params),
                    Map.of("X-RapidAPI-Key", key, "X-RapidAPI-Host", "jsearch.p.rapidapi.com"));

            for (JsonNode item : payload.path("data")) {
                String city = text(item, "job_city");
                String location = join(", ", city, text(item, "job_state"),
                        Values.firstNonBlank(text(item, "job_country"), query.country()));
                Double minimum = number(item, "job_min_salary");
                Double maximum = number(item, "job_max_salary");

                Map<String, Object> job = new LinkedHashMap<>();
                job.put("source", "jsearch");
                job.put("external_id", text(item, "job_id"));
                job.put("title", text(item, "job_title"));
                job.put("company", text(item, "employer_name"));
                job.put("location", location);
                job.put("country", country);
                job.put("description", text(item, "job_description"));
                job.put("apply_url", text(item, "job_apply_link"));
                job.put("source_url", text(item, "job_google_link"));
                job.put("salary_range", salaryRange(minimum, maximum, text(item, "job_salary_currency")));
                job.put("salary_min_raw", minimum);
                job.put("employment_type", text(item, "job_employment_type"));
                job.put("work_model", item.path("job_is_remote").asBoolean(false)
                        ? "Remote" : city.isEmpty() ? "" : "On-site");
                job.put("posted_at", text(item, "job_posted_at_datetime_utc"));
                job.put("fetched_at", fetchedAt);
                jobs.add(job);
            }
            pause(page, pages);
        }
        return jobs;
    }

    private List<Map<String, Object>> fetchAdzuna(Query query) {
        String appId = settings.adzunaAppId();
        String appKey = settings.adzunaAppKey();
        if (appId == null || appKey == null) {
            return List.of();
        }
        String fetchedAt = OffsetDateTime.now(ZoneOffset.UTC).toString();
        String country = query.country().toLowerCase(Locale.ROOT);
        if (!country.matches("[a-z]{2}")) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid country code");
        }
        List<Map<String, Object>> jobs = new ArrayList<>();
        int pages = settings.jobSearchPages();
        for (int page = 1; page <= pages; page++) {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("app_id", appId);
            params.put("app_key", appKey);
            params.put("results_per_page", 20);
            params.put("what", query.text());
            params.put("where", query.workModel().equalsIgnoreCase("remote") ? "remote" : query.location());
            params.put("content-type", "application/json");
            if (query.salaryMin() > 0) {
                params.put("salary_min", query.salaryMin());
            }
            switch (query.employmentType()) {
                case "FULLTIME" -> params.put("full_time", "1");
                case "PARTTIME" -> params.put("part_time", "1");
                case "CONTRACTOR" -> params.put("contract", "1");
                default -> {
                    // no Adzuna equivalent
                }
            }
            JsonNode payload = getJson("https://api.adzuna.com/v1/api/jobs/" + country + "/search/" + page
                    + "?" + Http.query(params), Map.of());

            for (JsonNode item : payload.path("results")) {
                Double minimum = number(item, "salary_min");
                Double maximum = number(item, "salary_max");

                Map<String, Object> job = new LinkedHashMap<>();
                job.put("source", "adzuna");
                job.put("external_id", text(item, "id"));
                job.put("title", text(item, "title"));
                job.put("company", text(item.path("company"), "display_name"));
                job.put("location", text(item.path("location"), "display_name"));
                job.put("country", country);
                job.put("description", text(item, "description"));
                job.put("apply_url", text(item, "redirect_url"));
                job.put("source_url", text(item, "adref"));
                job.put("salary_range", salaryRange(minimum, maximum, text(item, "salary_currency")));
                job.put("salary_min_raw", minimum);
                job.put("employment_type", query.employmentType());
                job.put("work_model", query.workModel());
                job.put("posted_at", text(item, "created"));
                job.put("fetched_at", fetchedAt);
                jobs.add(job);
            }
            pause(page, pages);
        }
        return jobs;
    }

    private JsonNode getJson(String url, Map<String, String> headers) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET();
        headers.forEach(request::header);
        try {
            HttpResponse<String> response = http.send(request.build());
            if (response.statusCode() >= 400) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "Search API error " + response.statusCode() + ": "
                        + Values.truncate(response.body(), 300));
            }
            return mapper.readTree(response.body());
        } catch (IOException error) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Search API unreachable: " + error.getMessage(), error);
        }
    }

    private void pause(int page, int pages) {
        if (page >= pages) {
            return;
        }
        try {
            Thread.sleep((long) (settings.jobSearchDelaySeconds() * 1000));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    private static List<Map<String, Object>> dedupe(List<Map<String, Object>> jobs) {
        Set<String> seen = new HashSet<>();
        List<Map<String, Object>> unique = new ArrayList<>();
        for (Map<String, Object> job : jobs) {
            String identity = Values.firstNonBlank(Values.str(job.get("external_id")),
                            Values.str(job.get("apply_url")), Values.str(job.get("title")))
                    .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
            if (seen.add(job.get("source") + "|" + identity)) {
                unique.add(job);
            }
        }
        return unique;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isValueNode() && !value.isNull() ? Values.clean(value.asText()) : "";
    }

    private static Double number(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isNumber()) {
            return value.asDouble();
        }
        try {
            return value.isTextual() ? Double.valueOf(value.asText()) : null;
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private static String join(String separator, String... parts) {
        return String.join(separator, java.util.Arrays.stream(parts).filter(p -> !p.isEmpty()).toList());
    }

    private static String salaryRange(Double minimum, Double maximum, String currency) {
        if (minimum == null && maximum == null) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        if (minimum != null) {
            parts.add(String.valueOf(minimum.longValue()));
        }
        if (maximum != null) {
            parts.add(String.valueOf(maximum.longValue()));
        }
        String result = String.join(" – ", parts);
        return currency.isEmpty() ? result : result + " " + currency.toUpperCase(Locale.ROOT);
    }
}
