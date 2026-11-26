package com.jobfinder.service;

import com.jobfinder.util.Values;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/** Pure logic that decides what goes into a generated CV: records, skills and headings. */
final class CvContent {

    private static final Set<String> TECHNICAL_TAGS = Set.of(
            "frontend", "backend", "fullstack", "mobile", "embedded-systems", "systems-programming", "devops",
            "cloud", "security", "testing", "software development", "ai-ml", "data-science", "data-engineering",
            "automation", "robotics", "blockchain", "ios", "android", "game-development", "ar-vr", "fintech");

    private static final Set<String> NON_TECHNICAL_TAGS = Set.of(
            "graphic-design", "motion-design", "video-editing", "content-creation", "photography", "3d-modelling",
            "product-design", "ui-ux", "product-management", "consulting", "technical-writing", "sales-growth",
            "operations", "hr-people", "legal-compliance");

    /** Skill groups in display order; a keyword goes into the first group that lists it. */
    private static final Map<String, Set<String>> SKILL_GROUPS = new LinkedHashMap<>();

    static {
        SKILL_GROUPS.put("Languages", Set.of("python", "javascript", "typescript", "java", "go", "rust", "sql",
                "kotlin", "swift", "c", "c++", "c#"));
        SKILL_GROUPS.put("Frameworks", Set.of("react", "next", "fastapi", "django", "flask", "langchain",
                "langgraph", "pandas", "numpy", "tailwind", "shadcn", "express"));
        SKILL_GROUPS.put("Databases", Set.of("postgresql", "postgres", "mysql", "sqlite", "mongodb", "supabase"));
        SKILL_GROUPS.put("AI/ML", Set.of("ai", "ml", "machine learning", "llm", "groq", "prompt engineering"));
        SKILL_GROUPS.put("Dev Tools", Set.of("git", "docker", "kubernetes", "aws", "ci", "cd", "linux", "bash",
                "testing", "pytest", "jest"));
        SKILL_GROUPS.put("Design", Set.of("ui", "ux", "figma", "photoshop", "illustrator", "branding"));
        SKILL_GROUPS.put("Video Editing", Set.of("premiere", "after effects", "aftereffects", "video", "editing",
                "final cut"));
        SKILL_GROUPS.put("Animation and Modeling", Set.of("blender", "3d", "modeling", "animation"));
        SKILL_GROUPS.put("General", Set.of());
    }

    private static final DateTimeFormatter MONTH_YEAR = DateTimeFormatter.ofPattern("MMM yyyy", Locale.US);

    private CvContent() {
    }

    static String normalizeKeyword(String value) {
        return Values.normalizeSpace(value).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9+\\- ]+", "");
    }

    static String jobText(Map<String, Object> job) {
        return String.join(" ", Stream.of(
                        Values.normalizeSpace(job.get("title")),
                        Values.normalizeSpace(job.get("company")),
                        Values.normalizeSpace(job.get("description")),
                        String.join(" ", Values.stringList(job.get("tags"))))
                .filter(part -> !part.isEmpty()).toList());
    }

    /** Keywords taken straight from the posting, used when no model answer is available. */
    static List<String> fallbackKeywords(Map<String, Object> job) {
        Set<String> keywords = new LinkedHashSet<>();
        for (String token : jobText(job).split("\\W+")) {
            String normalized = normalizeKeyword(token);
            if (normalized.length() >= 2) {
                keywords.add(normalized);
            }
        }
        return keywords.stream().limit(16).toList();
    }

    private static int score(Map<String, Object> item, Set<String> jobTags, Set<String> jobKeywords) {
        String tag = Values.normalizeSpace(item.get("tag")).toLowerCase(Locale.ROOT);
        int score = !tag.isEmpty() && jobTags.contains(tag) ? 3 : 0;
        Set<String> keywords = new HashSet<>();
        Values.stringList(item.get("keywords")).forEach(k -> keywords.add(normalizeKeyword(k)));
        keywords.remove("");
        return score + (int) keywords.stream().filter(jobKeywords::contains).count();
    }

    private static String recency(Map<String, Object> item) {
        return Values.firstNonBlank(Values.str(item.get("created_at")), Values.str(item.get("start_date")),
                Values.str(item.get("end_date")));
    }

    /** The best matching records (score of two or more), or the single best one when none qualifies. */
    static List<Map<String, Object>> select(List<Map<String, Object>> items, Set<String> jobTags,
                                            Set<String> jobKeywords, int limit) {
        record Scored(Map<String, Object> item, int score, String recency) {
        }
        List<Scored> ranked = items.stream()
                .map(item -> new Scored(item, score(item, jobTags, jobKeywords), recency(item)))
                .sorted(Comparator.comparingInt(Scored::score).thenComparing(Scored::recency).reversed())
                .toList();
        List<Map<String, Object>> selected = ranked.stream()
                .filter(entry -> entry.score() >= 2).map(Scored::item).limit(limit).toList();
        if (selected.isEmpty() && !ranked.isEmpty()) {
            return List.of(ranked.get(0).item());
        }
        return selected;
    }

    static String skillsHeading(Map<String, Object> job, Set<String> jobTags) {
        if (!jobTags.isEmpty() && NON_TECHNICAL_TAGS.containsAll(jobTags)) {
            return "Non-Technical Skills";
        }
        if (jobTags.stream().anyMatch(TECHNICAL_TAGS::contains)) {
            return "Technical Skills";
        }
        String text = jobText(job);
        return NON_TECHNICAL_TAGS.stream().anyMatch(text::contains) ? "Non-Technical Skills" : "Technical Skills";
    }

    static Map<String, List<String>> groupSkills(List<Map<String, Object>> records, List<String> jobKeywords) {
        Set<String> combined = new LinkedHashSet<>();
        records.forEach(item -> Values.stringList(item.get("keywords"))
                .forEach(k -> combined.add(normalizeKeyword(k))));
        jobKeywords.forEach(k -> combined.add(normalizeKeyword(k)));
        combined.remove("");

        Map<String, List<String>> buckets = new LinkedHashMap<>();
        SKILL_GROUPS.keySet().forEach(name -> buckets.put(name, new ArrayList<>()));
        for (String keyword : combined) {
            String group = SKILL_GROUPS.entrySet().stream()
                    .filter(entry -> entry.getValue().contains(keyword))
                    .map(Map.Entry::getKey).findFirst().orElse("General");
            buckets.get(group).add(keyword);
        }
        buckets.values().removeIf(List::isEmpty);
        return buckets;
    }

    static String formatDate(Object value) {
        String text = Values.str(value);
        if (text.isEmpty()) {
            return "Present";
        }
        String day = Values.truncate(text, 10);
        try {
            return LocalDate.parse(day).format(MONTH_YEAR);
        } catch (DateTimeParseException error) {
            return day;
        }
    }

    static String dateRange(Object start, Object end) {
        return formatDate(start) + " -- " + formatDate(end);
    }
}
