package com.jobfinder.service;

import com.jobfinder.db.Database;
import com.jobfinder.dto.JobDtos.SearchFilters;
import com.jobfinder.util.Values;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Two tier job filtering.
 * <ul>
 * <li>Tier 0: free algorithmic checks (age, region locks, seniority, skill overlap).</li>
 * <li>Tier 1: AI gate for the inconclusive jobs, capped per search.</li>
 * </ul>
 */
@Service
public class JobFilterService {

    private static final Logger LOG = LoggerFactory.getLogger(JobFilterService.class);

    private static final int MAX_AI_CALLS = 8;
    private static final java.util.concurrent.ExecutorService GATE_POOL =
            java.util.concurrent.Executors.newFixedThreadPool(2, runnable -> {
                Thread thread = new Thread(runnable, "job-gate");
                thread.setDaemon(true);
                return thread;
            });
    private static final int MAX_JOB_AGE_DAYS = 30;
    private static final double ACCEPT_THRESHOLD = 0.20;
    private static final int SENIORITY_GAP_HARD = 4;
    private static final String INCONCLUSIVE_REASON = "Relevant overlap found with your profile";

    /** Years-of-experience requirements, checked in this order. */
    private static final List<Pattern> EXPERIENCE_PATTERNS = List.of(
            insensitive("(\\d+)\\s*[-–]\\s*(\\d+)\\s*\\+?\\s*(?:years?|yrs?)\\s*(?:of\\s+)?(?:experience|exp)"),
            insensitive("(\\d+)\\s*\\+\\s*(?:years?|yrs?)\\s*(?:of\\s+)?(?:experience|exp)"),
            insensitive("(?:minimum|at\\s+least|min\\.?|>)\\s*(\\d+)\\s*(?:years?|yrs?)"),
            insensitive("(\\d+)\\s*(?:years?|yrs?)\\s*(?:\\+\\s*)?(?:of\\s+)?"
                    + "(?:relevant\\s+|total\\s+|industry\\s+|work\\s+|professional\\s+)?(?:experience|exp\\b)"));

    private static final List<Pattern> REGION_BLOCKERS = List.of(
            insensitive("(?:must\\s+be|only|exclusively)\\s+(?:us|uk|eu|canadian|australian)\\s+"
                    + "(?:citizen|resident|national|based)"),
            insensitive("authoriz(?:ed|ation)\\s+to\\s+work\\s+in\\s+(?:the\\s+)?"
                    + "(?:us|usa|uk|canada|australia|eu|europe)\\b"),
            insensitive("(?:us|uk|eu|canada|australia)\\s+(?:citizens?\\s+only|residents?\\s+only|"
                    + "nationals?\\s+only)"),
            insensitive("no\\s+(?:international|overseas|offshore|india|asian)\\s+"
                    + "(?:candidates?|applicants?|resumes?)"));

    private static final Set<String> UNRESTRICTED_COUNTRIES = Set.of("us", "gb", "uk", "ca", "au", "de", "sg");

    private enum Decision { ACCEPT, REJECT, INCONCLUSIVE }

    private record Screening(Decision decision, String reason) {
    }

    /** The user data the filters compare jobs against. */
    private record UserContext(
            Map<String, Object> profile, Map<String, Object> constraints,
            List<String> skills, int experienceYears, String country) {
    }

    private final Database db;
    private final JobGateService gate;

    public JobFilterService(Database db, JobGateService gate) {
        this.db = db;
        this.gate = gate;
    }

    private static Pattern insensitive(String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
    }

    /** Returns the passing jobs, enriched and sorted most recent first. */
    public List<Map<String, Object>> filter(String userId, List<Map<String, Object>> jobs, SearchFilters filters) {
        UserContext user = loadContext(userId, filters);

        List<Map<String, Object>> accepted = new ArrayList<>();
        List<Map<String, Object>> inconclusive = new ArrayList<>();
        for (Map<String, Object> job : jobs) {
            Screening screening = screen(job, user);
            if (screening.decision() == Decision.ACCEPT) {
                accepted.add(algorithmic(job, screening.reason()));
            } else if (screening.decision() == Decision.INCONCLUSIVE) {
                inconclusive.add(job);
            }
        }

        if (!inconclusive.isEmpty()) {
            if (user.skills().isEmpty()) {
                // No skills on file yet: keep the jobs so that the search is not empty.
                inconclusive.forEach(job -> accepted.add(algorithmic(job, INCONCLUSIVE_REASON)));
            } else {
                // Screen the first MAX_AI_CALLS with the AI in parallel; the rest already matched the tag,
                // so keep them unscreened instead of throwing them away.
                List<Map<String, Object>> screened = inconclusive.subList(0, Math.min(MAX_AI_CALLS, inconclusive.size()));
                List<java.util.concurrent.CompletableFuture<List<Map<String, Object>>>> calls = screened.stream()
                        .map(job -> java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                            List<Map<String, Object>> out = new ArrayList<>();
                            gateJob(user, job, out);
                            return out;
                        }, GATE_POOL))
                        .toList();
                calls.forEach(call -> accepted.addAll(call.join()));
                inconclusive.stream().skip(screened.size())
                        .forEach(job -> accepted.add(algorithmic(job, "Matches your tag (not AI-screened)")));
            }
        }

        accepted.sort(Comparator.comparing(
                (Map<String, Object> job) -> Values.str(job.get("posted_at"))).reversed());
        return accepted;
    }

    private void gateJob(UserContext user, Map<String, Object> job, List<Map<String, Object>> accepted) {
        try {
            JobGateService.Verdict verdict = gate.evaluate(user.profile(), user.constraints(), job);
            if (verdict.passes()) {
                Map<String, Object> enriched = new LinkedHashMap<>(job);
                enriched.put("brief_description", verdict.briefDescription());
                enriched.put("tech_stack", verdict.techStack());
                enriched.put("why_fit", verdict.whyFit());
                enriched.put("matching_experiences", verdict.matchingExperiences());
                enriched.put("matching_projects", verdict.matchingProjects());
                enriched.put("gate_provider", verdict.provider());
                accepted.add(enriched);
            }
        } catch (RuntimeException error) {
            LOG.warn("Job gate failed, keeping the job: {}", error.getMessage());
            accepted.add(algorithmic(job, INCONCLUSIVE_REASON));
        }
    }

    private UserContext loadContext(String userId, SearchFilters filters) {
        Map<String, Object> constraints = new LinkedHashMap<>();
        constraints.put("country", filters.country());
        constraints.put("salary_min", filters.salaryMin());
        constraints.put("employment_type", filters.employmentType());
        constraints.put("work_model", filters.workModel());

        Map<String, Object> user = db.fetchOne("auth/get_user_by_id", userId);
        if (user == null) {
            return new UserContext(Map.of(), constraints, List.of(), -1, filters.country());
        }
        Set<String> skills = new LinkedHashSet<>();
        Stream.concat(db.fetchAll("cv/get_user_exp_keywords", userId).stream(),
                        db.fetchAll("cv/get_user_proj_keywords", userId).stream())
                .forEach(row -> skills.add(Values.str(row.get("keyword"))));
        List<String> tags = db.fetchAll("onboarding/get_user_selected_tags", userId).stream()
                .map(row -> Values.str(row.get("tag_name"))).toList();
        List<Map<String, Object>> experiences = db.fetchAll("cv/get_user_experiences_detail", userId).stream()
                .map(row -> Map.<String, Object>of(
                        "role", Values.str(row.get("role")),
                        "company", Values.str(row.get("company")),
                        "description", Values.str(row.get("description")),
                        "keywords", Values.stringList(row.get("keywords"))))
                .toList();
        List<Map<String, Object>> projects = db.fetchAll("cv/get_user_project_summaries", userId).stream()
                .map(row -> Map.<String, Object>of(
                        "name", Values.str(row.get("name")),
                        "description", Values.str(row.get("description"))))
                .toList();

        // -1 means no recorded experience; 0 is a valid "<1 year" value.
        int years = user.get("experience_years") instanceof Number value
                ? value.intValue() : -1;

        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("job_title", Values.str(user.get("raw_job_title")));
        profile.put("bio", Values.str(user.get("bio")));
        profile.put("experience_years", years);
        profile.put("skills_detected", List.copyOf(skills));
        profile.put("tags", tags);
        profile.put("experiences", experiences);
        profile.put("projects", projects);
        return new UserContext(profile, constraints, List.copyOf(skills), years, filters.country());
    }

    private Screening screen(Map<String, Object> job, UserContext user) {
        String combined = (Values.str(job.get("title")) + " " + Values.str(job.get("description")))
                .toLowerCase(Locale.ROOT);

        OffsetDateTime posted = Values.parseTimestamp(Values.str(job.get("posted_at")));
        if (posted != null) {
            long age = Math.max(0, Duration.between(posted, OffsetDateTime.now()).toDays());
            if (age > MAX_JOB_AGE_DAYS) {
                return new Screening(Decision.REJECT, "Posted " + age + "d ago");
            }
        }

        if (!UNRESTRICTED_COUNTRIES.contains(user.country().toLowerCase(Locale.ROOT))
                && REGION_BLOCKERS.stream().anyMatch(p -> p.matcher(combined).find())) {
            return new Screening(Decision.REJECT, "Explicit region restriction");
        }

        Integer required = minimumYears(combined);
        if (required != null && user.experienceYears() >= 0
                && required - user.experienceYears() > SENIORITY_GAP_HARD) {
            return new Screening(Decision.REJECT, "Requires " + required + "+ years");
        }

        double overlap = skillOverlap(combined, user.skills());
        if (overlap >= ACCEPT_THRESHOLD) {
            return new Screening(Decision.ACCEPT, Math.round(overlap * 100) + "% skill overlap");
        }
        return new Screening(Decision.INCONCLUSIVE, INCONCLUSIVE_REASON);
    }

    static Integer minimumYears(String text) {
        for (Pattern pattern : EXPERIENCE_PATTERNS) {
            Matcher matcher = pattern.matcher(text);
            if (matcher.find()) {
                int lowest = Integer.MAX_VALUE;
                for (int group = 1; group <= matcher.groupCount(); group++) {
                    String digits = matcher.group(group);
                    // very long digit runs are not year counts
                    if (digits != null && digits.length() < 9) {
                        lowest = Math.min(lowest, Integer.parseInt(digits));
                    }
                }
                return lowest == Integer.MAX_VALUE ? null : lowest;
            }
        }
        return null;
    }

    /** Fraction of the user's skills that appear in the job text. */
    static double skillOverlap(String jobText, List<String> skills) {
        if (skills.isEmpty()) {
            return 0.0;
        }
        Set<String> jobTokens = stems(jobText);
        long matched = skills.stream()
                .filter(skill -> stems(skill).stream().anyMatch(jobTokens::contains))
                .count();
        return (double) matched / skills.size();
    }

    private static Set<String> stems(String text) {
        Set<String> result = new HashSet<>();
        for (String token : text.toLowerCase(Locale.ROOT).split("\\W+")) {
            if (token.length() > 2) {
                result.add(stem(token));
            }
        }
        return result;
    }

    private static String stem(String token) {
        String word = token.replaceAll("[^a-z0-9]", "");
        for (String suffix : List.of("tion", "ment", "ing", "ed", "er", "or", "ly", "es")) {
            if (word.length() > suffix.length() + 3 && word.endsWith(suffix)) {
                return word.substring(0, word.length() - suffix.length());
            }
        }
        return word.length() > 4 && word.endsWith("s") ? word.substring(0, word.length() - 1) : word;
    }

    private static Map<String, Object> algorithmic(Map<String, Object> job, String reason) {
        String text = Values.str(job.get("description")).strip();
        Map<String, Object> enriched = new LinkedHashMap<>(job);
        enriched.put("brief_description",
                text.length() > 240 ? text.substring(0, 240).stripTrailing() + "…" : text);
        enriched.put("tech_stack", List.of());
        enriched.put("why_fit", reason);
        enriched.put("matching_experiences", List.of());
        enriched.put("matching_projects", List.of());
        enriched.put("gate_provider", "algorithmic");
        return enriched;
    }
}
