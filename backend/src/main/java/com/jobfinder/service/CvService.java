package com.jobfinder.service;

import com.jobfinder.client.AiClientException;
import com.jobfinder.client.GroqClient;
import com.jobfinder.config.AppSettings;
import com.jobfinder.db.Database;
import com.jobfinder.dto.CvDtos.ConfirmRequest;
import com.jobfinder.dto.CvDtos.ConfirmResponse;
import com.jobfinder.dto.CvDtos.Education;
import com.jobfinder.dto.CvDtos.Experience;
import com.jobfinder.dto.CvDtos.Preview;
import com.jobfinder.dto.CvDtos.Project;
import com.jobfinder.util.Values;
import com.jobfinder.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Two step CV import: {@link #preview} parses the PDF without storing anything, then
 * {@link #confirm} writes the user-reviewed data in one transaction.
 */
@Service
public class CvService {

    private static final List<String> EXPERIENCE_TYPES = List.of(
            "advisor", "freelance", "full_time", "internship", "part_time", "unpaid_internship");
    private static final Set<String> DEGREE_LEVELS =
            Set.of("high_school", "diploma", "ug", "pg", "phd", "other");
    private static final String DEFAULT_EDUCATION_TAG = "research";

    private static final Map<String, String> KEYWORD_ALIASES = Map.ofEntries(
            Map.entry("reactjs", "react"), Map.entry("react.js", "react"),
            Map.entry("node.js", "node"), Map.entry("nodejs", "node"),
            Map.entry("next.js", "next"), Map.entry("nextjs", "next"),
            Map.entry("vue.js", "vue"), Map.entry("vuejs", "vue"),
            Map.entry("tensorflow.js", "tensorflowjs"), Map.entry("postgres", "postgresql"),
            Map.entry("adobe photoshop", "photoshop"), Map.entry("adobe illustrator", "illustrator"),
            Map.entry("adobe premiere", "premiere"), Map.entry("adobe premiere pro", "premiere"),
            Map.entry("premiere pro", "premiere"), Map.entry("after effects", "aftereffects"));

    private static final String PROMPT = """
            You are a CV parser. Extract structured data from the following CV text.

            Allowed canonical tags (ONLY these for "tag" fields and "suggested_user_tags"):
            {tags}

            Allowed experience_type values: {types}
            Allowed degree_level values: high_school, diploma, ug (bachelor/undergraduate), pg (master/postgraduate/MBA), phd (doctorate), other

            Return STRICT JSON (no prose, no markdown fences):
            {
              "education": [
                {
                  "institution": "string",
                  "degree": "string (e.g. Bachelor of Technology, Master of Science, High School Diploma)",
                  "degree_level": "one of: high_school | diploma | ug | pg | phd | other",
                  "field_of_study": "string or null",
                  "start_date": "YYYY-MM-DD or null",
                  "end_date": "YYYY-MM-DD or null if current/ongoing",
                  "grade": "string or null (GPA, percentage, grade, etc.)",
                  "description": "string or null",
                  "tag": "one canonical tag most relevant to this qualification"
                }
              ],
              "experiences": [
                {
                  "company": "string",
                  "location": "string or null",
                  "role": "string",
                  "experience_type": "one of allowed types",
                  "start_date": "YYYY-MM-DD",
                  "end_date": "YYYY-MM-DD or null if current",
                  "description": "string",
                  "tag": "one canonical tag",
                  "keywords": ["lowercase tech keywords"]
                }
              ],
              "projects": [
                {
                  "name": "string",
                  "description": "string",
                  "github_url": "string or null",
                  "demo_url": "string or null",
                  "tag": "one canonical tag",
                  "keywords": ["lowercase tech keywords"]
                }
              ],
              "suggested_user_tags": ["up to 5 canonical tags"]
            }

            Rules:
            - Dates: assume day=01 if only month/year given. Year-only → YYYY-01-01.
            - tag MUST be exactly one of the allowed canonical tags.
            - keywords: lowercase, no version numbers, no duplicates.
            - Return empty arrays if nothing found.
            - Education is CRITICAL — extract all degrees, diplomas, and certifications.

            CV TEXT:
            \"""
            {text}
            \"""
            """;

    private final Database db;
    private final AppSettings settings;
    private final GroqClient groq;
    private final CvTextExtractor extractor;

    public CvService(Database db, AppSettings settings, GroqClient groq, CvTextExtractor extractor) {
        this.db = db;
        this.settings = settings;
        this.groq = groq;
        this.extractor = extractor;
    }

    static String normalizeKeyword(String raw) {
        String cleaned = Values.normalizeSpace(raw).toLowerCase(Locale.ROOT);
        return KEYWORD_ALIASES.getOrDefault(cleaned, cleaned);
    }

    // ── step 1: preview (no database writes) ────────────────────────────────

    public Preview preview(byte[] pdf) {
        String text = extractor.extractText(pdf);
        if (text.length() < settings.cvMinTextChars()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "CV appears to be a scanned image or empty. Paste text manually or upload a text-based PDF.");
        }
        List<String> tagNames = db.fetchAll("cv/get_all_canonical_tag_names").stream()
                .map(row -> Values.str(row.get("tag_name"))).toList();
        Set<String> tags = new HashSet<>(tagNames);
        Map<String, Object> parsed = parseWithGroq(text, tagNames);

        List<Education> education = objects(parsed, "education").map(item -> new Education(
                str(item, "institution"), str(item, "degree"),
                DEGREE_LEVELS.contains(str(item, "degree_level")) ? str(item, "degree_level") : "other",
                str(item, "field_of_study"), str(item, "start_date"), str(item, "end_date"),
                str(item, "grade"), str(item, "description"),
                tags.contains(str(item, "tag")) ? str(item, "tag") : DEFAULT_EDUCATION_TAG)).toList();

        List<Experience> experiences = objects(parsed, "experiences").map(item -> new Experience(
                str(item, "company"), str(item, "location"), str(item, "role"),
                EXPERIENCE_TYPES.contains(str(item, "experience_type")) ? str(item, "experience_type") : "full_time",
                str(item, "start_date"), str(item, "end_date"), str(item, "description"),
                tags.contains(str(item, "tag")) ? str(item, "tag") : null,
                keywords(Values.stringList(item.get("keywords"))))).toList();

        List<Project> projects = objects(parsed, "projects").map(item -> new Project(
                str(item, "name"), str(item, "description"), str(item, "github_url"), str(item, "demo_url"),
                tags.contains(str(item, "tag")) ? str(item, "tag") : null,
                keywords(Values.stringList(item.get("keywords"))))).toList();

        List<String> suggested = Values.stringList(parsed.get("suggested_user_tags")).stream()
                .filter(tags::contains).limit(5).toList();

        return new Preview(education, experiences, projects, suggested, extractor.contactDetails(text));
    }

    private Map<String, Object> parseWithGroq(String text, List<String> tagNames) {
        if (settings.groqApiKey() == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "GROQ_API_KEY not configured");
        }
        // The CV text is substituted last so that its content is never treated as a placeholder.
        String prompt = PROMPT
                .replace("{tags}", String.join(", ", tagNames))
                .replace("{types}", String.join(", ", EXPERIENCE_TYPES))
                .replace("{text}", text);
        Duration timeout = Duration.ofSeconds(90);
        String answer;
        try {
            answer = groq.complete(prompt, settings.groqModel(), 0.0, null, true, timeout);
        } catch (AiClientException jsonModeFailure) {
            try {
                answer = groq.complete(prompt, settings.groqModel(), 0.0, null, false, timeout);
            } catch (AiClientException error) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "Groq call failed: " + error.getMessage(), error);
            }
        }
        try {
            return groq.parseObject(answer);
        } catch (AiClientException error) {
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "Groq returned invalid JSON: " + error.getMessage(), error);
        }
    }

    @SuppressWarnings("unchecked")
    private static Stream<Map<String, Object>> objects(Map<String, Object> parsed, String key) {
        if (!(parsed.get(key) instanceof List<?> list)) {
            return Stream.empty();
        }
        return list.stream().filter(item -> item instanceof Map).map(item -> (Map<String, Object>) item);
    }

    private static String str(Map<String, Object> item, String key) {
        Object value = item.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static List<String> keywords(List<String> raw) {
        return raw.stream().map(CvService::normalizeKeyword).filter(k -> !k.isEmpty()).distinct().toList();
    }

    // ── step 2: store confirmed data ────────────────────────────────────────

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(CvService.class);

    /** Replaces the user's CV data. Everything happens in one transaction. */
    public ConfirmResponse confirm(String userId, ConfirmRequest request) {
        try {
            return db.inTransaction(() -> store(userId, request));
        } catch (ApiException error) {
            throw error;
        } catch (RuntimeException error) {
            LOG.error("CV confirm failed for user {}", userId, error);
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to store CV data: " + error.getMessage(), error);
        }
    }

    private ConfirmResponse store(String userId, ConfirmRequest request) {
        Map<String, String> tagIds = new LinkedHashMap<>();
        db.fetchAll("onboarding/get_canonical_tags")
                .forEach(row -> tagIds.put(Values.str(row.get("tag_name")), Values.str(row.get("tag_id"))));

        db.execute("cv/clear_user_education", userId);
        db.execute("cv/clear_user_cv_data", userId);
        db.execute("cv/clear_user_projects", userId);

        int educationCount = storeEducation(userId, request.education(), tagIds);
        int experienceCount = storeExperiences(userId, request.experiences(), tagIds);
        int projectCount = storeProjects(userId, request.projects(), tagIds);

        // Every tag mentioned by the CV becomes a selected tag.
        Set<String> cvTags = new LinkedHashSet<>();
        request.education().forEach(e -> cvTags.add(e.tag().isEmpty() ? DEFAULT_EDUCATION_TAG : e.tag()));
        request.experiences().forEach(e -> cvTags.add(e.tag()));
        request.projects().forEach(p -> cvTags.add(p.tag()));
        cvTags.addAll(request.suggestedTags());

        tagIds.values().forEach(id -> db.execute("onboarding/insert_user_assigned_tag", userId, id));
        cvTags.stream().filter(Objects::nonNull).map(tagIds::get).filter(Objects::nonNull)
                .forEach(id -> db.execute("onboarding/insert_user_selected_tag", userId, id));
        db.execute("auth/mark_cv_uploaded", userId);

        List<String> suggested = request.suggestedTags().stream()
                .filter(tag -> tag != null && tagIds.containsKey(tag)).limit(5).toList();
        return new ConfirmResponse(educationCount, experienceCount, projectCount, suggested);
    }

    private int storeEducation(String userId, List<Education> items, Map<String, String> tagIds) {
        int count = 0;
        for (Education item : items) {
            String institution = item.institution().strip();
            String degree = item.degree().strip();
            // user_education.tag_id is NOT NULL, so fall back to the default tag
            String tagId = tagIds.getOrDefault(item.tag(), tagIds.get(DEFAULT_EDUCATION_TAG));
            if (institution.isEmpty() || degree.isEmpty() || tagId == null) {
                continue;
            }
            db.fetchOne("cv/insert_education", userId, institution, degree,
                    DEGREE_LEVELS.contains(item.degreeLevel()) ? item.degreeLevel() : "other",
                    Values.nullIfEmpty(item.fieldOfStudy()), parseDate(item.startDate()), parseDate(item.endDate()),
                    Values.nullIfEmpty(item.grade()), Values.nullIfEmpty(item.description()), tagId);
            count++;
        }
        return count;
    }

    private int storeExperiences(String userId, List<Experience> items, Map<String, String> tagIds) {
        int count = 0;
        for (Experience item : items) {
            String company = item.company().strip();
            String role = item.role().strip();
            LocalDate start = parseDate(item.startDate());
            if (!EXPERIENCE_TYPES.contains(item.experienceType()) || company.isEmpty() || role.isEmpty()
                    || start == null) {
                continue;
            }
            Map<String, Object> row = db.fetchOne("cv/insert_experience", userId, company,
                    Values.nullIfEmpty(item.location()), role, item.experienceType(), start,
                    parseDate(item.endDate()), Values.nullIfEmpty(item.description()),
                    tagId(tagIds, item.tag()));
            storeKeywords("cv/insert_experience_keyword", row.get("experience_id"), item.keywords());
            count++;
        }
        return count;
    }

    private int storeProjects(String userId, List<Project> items, Map<String, String> tagIds) {
        int count = 0;
        for (Project item : items) {
            String name = item.name().strip();
            if (name.isEmpty()) {
                continue;
            }
            Map<String, Object> row = db.fetchOne("cv/insert_project", userId, name,
                    Values.nullIfEmpty(item.description()), Values.nullIfEmpty(item.githubUrl()),
                    Values.nullIfEmpty(item.demoUrl()), tagId(tagIds, item.tag()));
            storeKeywords("cv/insert_project_keyword", row.get("project_id"), item.keywords());
            count++;
        }
        return count;
    }

    private void storeKeywords(String linkQuery, Object ownerId, List<String> raw) {
        for (String keyword : keywords(new ArrayList<>(raw.stream().filter(Objects::nonNull).toList()))) {
            Map<String, Object> row = db.fetchOne("cv/get_or_create_keyword", keyword, keyword);
            db.execute(linkQuery, ownerId, row.get("keyword_id"));
        }
    }

    private static String tagId(Map<String, String> tagIds, String tag) {
        return tag == null ? null : tagIds.get(tag);
    }

    private static LocalDate parseDate(String value) {
        if (Values.blank(value)) {
            return null;
        }
        try {
            return LocalDate.parse(value.strip());
        } catch (DateTimeParseException error) {
            return null;
        }
    }
}
