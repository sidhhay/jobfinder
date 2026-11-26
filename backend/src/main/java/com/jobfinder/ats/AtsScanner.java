package com.jobfinder.ats;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.jobfinder.client.Http;
import com.jobfinder.util.Values;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Command line tool that reads jobs straight from company job boards (Greenhouse, Ashby, Lever)
 * and exports them as text, CSV and JSON. It is independent of the web server.
 *
 * <p>Options: {@code --portals <file>}, {@code --limit <n>}, {@code --output <folder>}.
 */
public final class AtsScanner {

    /** One job read from a board. */
    public record Job(
            String source, String externalId, String title, String company, String location, String description,
            String applyUrl, String publisher, Double salaryMin, Double salaryMax, String currency,
            String postedAt, String fetchedAt) {
    }

    private record Board(String type, String url) {
    }

    private record Result(List<Job> jobs, String error) {
    }

    private static final Pattern ASHBY = Pattern.compile("jobs\\.ashbyhq\\.com/([^/?#]+)");
    private static final Pattern LEVER = Pattern.compile("jobs\\.lever\\.co/([^/?#]+)");
    private static final Pattern GREENHOUSE =
            Pattern.compile("(?:boards|job-boards(?:\\.eu)?)\\.greenhouse\\.io/([^/?#]+)");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Http HTTP = new Http();

    private AtsScanner() {
    }

    public static void main(String[] args) throws IOException {
        Map<String, String> options = new LinkedHashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            options.put(args[i], args[i + 1]);
        }
        JsonNode config = loadPortals(options.get("--portals"));
        int limit = Integer.parseInt(options.getOrDefault("--limit", "0"));
        Path output = Path.of(options.getOrDefault("--output", "exports/ats"));

        List<String> positive = words(config.path("title_filter").path("positive"));
        List<String> negative = words(config.path("title_filter").path("negative"));
        List<JsonNode> companies = new ArrayList<>();
        config.path("tracked_companies").forEach(company -> {
            if (company.path("enabled").asBoolean(true)) {
                companies.add(company);
            }
        });
        List<JsonNode> chosen = limit > 0 ? companies.subList(0, Math.min(limit, companies.size())) : companies;

        List<CompletableFuture<Result>> pending = chosen.stream()
                .map(company -> CompletableFuture.supplyAsync(() -> scan(company, positive, negative)))
                .toList();
        List<Job> jobs = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (CompletableFuture<Result> future : pending) {
            Result result = future.join();
            jobs.addAll(result.jobs());
            if (result.error() != null) {
                errors.add(result.error());
            }
        }

        Map<String, Job> unique = new LinkedHashMap<>();
        jobs.forEach(job -> unique.putIfAbsent(job.source() + "|"
                + identity(Values.firstNonBlank(job.externalId(), job.applyUrl(), job.title())) + "|"
                + identity(job.location()), job));
        export(List.copyOf(unique.values()), output);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("companies_considered", chosen.size());
        summary.put("jobs_before_dedupe", jobs.size());
        summary.put("jobs_after_dedupe", unique.size());
        summary.put("errors", errors);
        summary.put("output_dir", output.toAbsolutePath().toString());
        System.out.println(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(summary));
    }

    private static JsonNode loadPortals(String file) throws IOException {
        if (file != null) {
            return MAPPER.readTree(Files.readString(Path.of(file)));
        }
        try (InputStream bundled = AtsScanner.class.getResourceAsStream("/ats_portals.json")) {
            if (bundled == null) {
                throw new IOException("ats_portals.json is missing");
            }
            return MAPPER.readTree(bundled);
        }
    }

    private static Result scan(JsonNode company, List<String> positive, List<String> negative) {
        String name = Values.clean(company.path("name").asText("unknown"));
        Board board = detect(company);
        if (board == null) {
            return new Result(List.of(), name + ": no supported ATS detected");
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(board.url()))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", "applybot-tracker/ats-scanner")
                    .GET().build();
            HttpResponse<String> response = HTTP.send(request);
            if (response.statusCode() >= 400) {
                return new Result(List.of(), name + ": HTTP " + response.statusCode() + " for " + board.url());
            }
            List<Job> jobs = parse(board.type(), MAPPER.readTree(response.body()), name).stream()
                    .filter(job -> allowed(job.title(), positive, negative))
                    .toList();
            return new Result(jobs, null);
        } catch (IOException | IllegalArgumentException error) {
            return new Result(List.of(), name + ": " + error.getMessage());
        }
    }

    private static Board detect(JsonNode company) {
        String api = Values.clean(company.path("api").asText(""));
        if (api.contains("greenhouse")) {
            return new Board("greenhouse", api);
        }
        String careers = Values.clean(company.path("careers_url").asText(""));
        Matcher matcher = ASHBY.matcher(careers);
        if (matcher.find()) {
            return new Board("ashby", "https://api.ashbyhq.com/posting-api/job-board/" + matcher.group(1)
                    + "?includeCompensation=true");
        }
        matcher = LEVER.matcher(careers);
        if (matcher.find()) {
            return new Board("lever", "https://api.lever.co/v0/postings/" + matcher.group(1));
        }
        matcher = GREENHOUSE.matcher(careers);
        if (matcher.find()) {
            return new Board("greenhouse",
                    "https://boards-api.greenhouse.io/v1/boards/" + matcher.group(1) + "/jobs?content=true");
        }
        return null;
    }

    private static List<Job> parse(String type, JsonNode payload, String company) {
        String fetchedAt = OffsetDateTime.now(ZoneOffset.UTC).toString();
        List<Job> jobs = new ArrayList<>();
        for (JsonNode item : type.equals("lever") ? payload : payload.path("jobs")) {
            switch (type) {
                case "greenhouse" -> jobs.add(new Job("ats_greenhouse", text(item, "id"), text(item, "title"),
                        company, text(item.path("location"), "name"), text(item, "content"),
                        text(item, "absolute_url"), "Greenhouse", null, null, "",
                        timestamp(Values.firstNonBlank(item.path("updated_at").asText(""),
                                item.path("first_published").asText(""))), fetchedAt));
                case "ashby" -> {
                    JsonNode pay = item.path("compensation");
                    jobs.add(new Job("ats_ashby", text(item, "id"), text(item, "title"), company,
                            text(item, "location"),
                            Values.firstNonBlank(text(item, "descriptionPlain"), text(item, "descriptionHtml")),
                            text(item, "jobUrl"), "Ashby", number(pay, "minCompensation"),
                            number(pay, "maxCompensation"), text(pay, "currencyCode"),
                            timestamp(item.path("publishedDate").asText("")), fetchedAt));
                }
                default -> jobs.add(new Job("ats_lever", text(item, "id"), text(item, "text"), company,
                        text(item.path("categories"), "location"),
                        Values.firstNonBlank(text(item, "descriptionPlain"), text(item, "description")),
                        text(item, "hostedUrl"), "Lever", null, null, "",
                        timestamp(item.path("createdAt").asText("")), fetchedAt));
            }
        }
        return jobs;
    }

    /** Converts epoch numbers or ISO text to an ISO timestamp in UTC. */
    private static String timestamp(String value) {
        String text = value.strip();
        if (text.matches("\\d{1,18}")) {
            long number = Long.parseLong(text);
            Instant instant = number > 10_000_000_000L ? Instant.ofEpochMilli(number) : Instant.ofEpochSecond(number);
            return instant.toString();
        }
        OffsetDateTime parsed = Values.parseTimestamp(text);
        return parsed == null ? text : parsed.toInstant().toString();
    }

    private static boolean allowed(String title, List<String> positive, List<String> negative) {
        String lowered = title.toLowerCase(Locale.ROOT);
        return (positive.isEmpty() || positive.stream().anyMatch(lowered::contains))
                && negative.stream().noneMatch(lowered::contains);
    }

    private static List<String> words(JsonNode list) {
        List<String> result = new ArrayList<>();
        list.forEach(item -> {
            String word = Values.clean(item.asText("")).toLowerCase(Locale.ROOT);
            if (!word.isEmpty()) {
                result.add(word);
            }
        });
        return result;
    }

    private static String identity(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").strip();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isValueNode() && !value.isNull() ? Values.clean(value.asText()) : "";
    }

    private static Double number(JsonNode node, String field) {
        return node.path(field).isNumber() ? node.path(field).asDouble() : null;
    }

    private static void export(List<Job> jobs, Path folder) throws IOException {
        Files.createDirectories(folder);
        StringBuilder text = new StringBuilder();
        StringBuilder csv = new StringBuilder("source,external_id,title,company,location,publisher,posted_at,"
                + "salary_min,salary_max,currency,apply_url,description,fetched_at\n");
        int index = 1;
        for (Job job : jobs) {
            text.append(index++).append(". ").append(job.title()).append('\n')
                    .append("Company: ").append(job.company()).append('\n')
                    .append("Location: ").append(Values.firstNonBlank(job.location(), "Unknown")).append('\n')
                    .append("Source: ").append(job.source()).append(" / ").append(job.publisher()).append('\n')
                    .append("Posted: ").append(Values.firstNonBlank(job.postedAt(), "Unknown")).append('\n')
                    .append("Apply: ").append(Values.firstNonBlank(job.applyUrl(), "No link")).append('\n')
                    .append("Description: ").append(Values.truncate(job.description(), 500)).append("\n\n");
            csv.append(String.join(",", List.of(
                    csvCell(job.source()), csvCell(job.externalId()), csvCell(job.title()), csvCell(job.company()),
                    csvCell(job.location()), csvCell(job.publisher()), csvCell(job.postedAt()),
                    csvCell(job.salaryMin()), csvCell(job.salaryMax()), csvCell(job.currency()),
                    csvCell(job.applyUrl()), csvCell(job.description()), csvCell(job.fetchedAt()))))
                    .append('\n');
        }
        Files.writeString(folder.resolve("jobs.txt"), text, StandardCharsets.UTF_8);
        Files.writeString(folder.resolve("jobs.csv"), csv, StandardCharsets.UTF_8);
        MAPPER.copy().enable(SerializationFeature.INDENT_OUTPUT)
                .writeValue(folder.resolve("jobs.json").toFile(), jobs);
    }

    private static String csvCell(Object value) {
        String cell = Values.str(value);
        // Stop spreadsheet programs from treating downloaded text as a formula.
        if (!cell.isEmpty() && "=+-@".indexOf(cell.charAt(0)) >= 0) {
            cell = "'" + cell;
        }
        return "\"" + cell.replace("\"", "\"\"") + "\"";
    }
}
