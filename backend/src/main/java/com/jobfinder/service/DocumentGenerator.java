package com.jobfinder.service;

import com.jobfinder.client.AiClientException;
import com.jobfinder.client.GroqClient;
import com.jobfinder.config.AppSettings;
import com.jobfinder.db.Database;
import com.jobfinder.util.Values;
import com.jobfinder.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Builds tailored CV and cover letter HTML for one application. */
@Component
public class DocumentGenerator {

    /** A rendered document plus the data it was built from. */
    public record Draft(
            String html, Map<String, Object> profileSnapshot, Map<String, Object> jobSnapshot,
            Map<String, Object> generationParams) {
    }

    private record Context(
            Map<String, Object> job, Map<String, Object> user, List<Map<String, Object>> education,
            List<Map<String, Object>> experiences, List<Map<String, Object>> projects) {
    }

    private static final String TEMPLATE_NAME = "jobful-application-v1";
    private static final DateTimeFormatter LETTER_DATE = DateTimeFormatter.ofPattern("MMMM dd, yyyy", Locale.US);

    private final Database db;
    private final GroqClient groq;
    private final AppSettings settings;
    private final TemplateRenderer templates;

    public DocumentGenerator(Database db, GroqClient groq, AppSettings settings, TemplateRenderer templates) {
        this.db = db;
        this.groq = groq;
        this.settings = settings;
        this.templates = templates;
    }

    public Draft buildCv(String userId, Map<String, Object> application) {
        Context context = load(userId, application);
        Map<String, Object> job = context.job();
        List<String> keywords = jobKeywords(job);
        Set<String> tags = lowerTags(job);

        List<Map<String, Object>> experiences =
                CvContent.select(context.experiences(), tags, new HashSet<>(keywords), 3);
        List<Map<String, Object>> projects =
                CvContent.select(context.projects(), tags, new HashSet<>(keywords), 4);

        List<Map<String, Object>> selected = new ArrayList<>(experiences);
        selected.addAll(projects);
        Map<String, List<String>> skills = CvContent.groupSkills(selected, keywords);
        String heading = CvContent.skillsHeading(job, tags);
        Map<String, Object> profile = profile(context.user());

        Map<String, Object> model = new LinkedHashMap<>();
        model.put("profile", profile);
        model.put("education", context.education().stream().map(DocumentGenerator::withDates).toList());
        model.put("experiences", experiences.stream().map(item -> {
            Map<String, Object> view = withDates(item);
            view.put("bullet", rewriteExperience(item, job, keywords));
            return view;
        }).toList());
        model.put("projects", projects.stream().map(item -> {
            Map<String, Object> view = new LinkedHashMap<>(item);
            view.put("bullets", rewriteProject(item, job, keywords));
            return view;
        }).toList());
        model.put("skills", skills);
        model.put("skills_heading", heading);
        model.put("job", job);

        Map<String, Object> params = generationParams("cv", job, keywords, experiences, projects);
        params.put("excluded_experience_ids", excluded(context.experiences(), experiences, "experience_id"));
        params.put("excluded_project_ids", excluded(context.projects(), projects, "project_id"));
        params.put("selected_skills", skills);
        params.put("skills_heading", heading);

        return new Draft(templates.render("cv_template.html", model), profile, jobSnapshot(job, keywords), params);
    }

    public Draft buildCoverLetter(String userId, Map<String, Object> application) {
        Context context = load(userId, application);
        Map<String, Object> job = context.job();
        List<String> keywords = jobKeywords(job);
        Set<String> tags = lowerTags(job);

        List<Map<String, Object>> experiences =
                CvContent.select(context.experiences(), tags, new HashSet<>(keywords), 1);
        List<Map<String, Object>> projects =
                CvContent.select(context.projects(), tags, new HashSet<>(keywords), 1);
        Map<String, Object> experience = experiences.isEmpty() ? null : experiences.get(0);
        Map<String, Object> project = projects.isEmpty() ? null : projects.get(0);
        Map<String, Object> profile = profile(context.user());

        Map<String, Object> model = new LinkedHashMap<>();
        model.put("profile", profile);
        model.put("job", job);
        model.put("body", letterBody(context.user(), job, keywords, experience, project));
        model.put("today_date", LocalDate.now().format(LETTER_DATE));

        Map<String, Object> params = generationParams("cover_letter", job, keywords, experiences, projects);
        return new Draft(templates.render("cover_letter_template.html", model),
                profile, jobSnapshot(job, keywords), params);
    }

    // ── data loading ────────────────────────────────────────────────────────

    private Context load(String userId, Map<String, Object> application) {
        Map<String, Object> user = db.fetchOne("auth/get_user_by_id", userId);
        if (user == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "User not found");
        }
        Map<String, Object> job = new LinkedHashMap<>();
        job.put("job_id", Values.str(application.get("job_id")));
        for (String field : List.of("title", "company", "description", "location")) {
            job.put(field, Values.str(application.get(field)));
        }
        job.put("tags", db.fetchAll("documents/get_job_tags", job.get("job_id")).stream()
                .map(row -> Values.str(row.get("tag_name"))).toList());
        return new Context(job, user,
                db.fetchAll("cv/get_user_education", userId),
                db.fetchAll("cv/get_user_experiences_detail", userId),
                db.fetchAll("cv/get_user_projects_detail", userId));
    }

    // ── language model helpers ──────────────────────────────────────────────

    private String ask(String prompt, int maxTokens) {
        return groq.complete(prompt, settings.groqModel(), 0.25, maxTokens, false, Duration.ofSeconds(60));
    }

    private List<String> jobKeywords(Map<String, Object> job) {
        String prompt = "Extract the most relevant job keywords from the posting.\n"
                + "Return ONLY a JSON object with a key named keywords containing an array of strings.\n"
                + "Prefer skills, tools, frameworks, domain phrases, and role-specific nouns.\n"
                + "TITLE: " + job.get("title") + "\n"
                + "DESCRIPTION: " + Values.truncate(Values.str(job.get("description")), 3500) + "\n";
        try {
            String answer = groq.complete(prompt, settings.groqModel(), 0.0, null, true,
                    Duration.ofSeconds(30));
            List<String> keywords = Values.stringList(groq.parseObject(answer).get("keywords")).stream()
                    .map(CvContent::normalizeKeyword).filter(k -> !k.isEmpty()).distinct().limit(16).toList();
            if (!keywords.isEmpty()) {
                return keywords;
            }
        } catch (AiClientException ignored) {
            // use the words of the posting instead
        }
        return CvContent.fallbackKeywords(job);
    }

    private String rewriteExperience(Map<String, Object> experience, Map<String, Object> job, List<String> keywords) {
        String prompt = "Rewrite this experience for a CV as one concise sentence.\n"
                + "Do not invent technologies. Use only the supplied keywords when relevant.\n"
                + "Keep it specific and professional.\n"
                + "JOB TITLE: " + job.get("title") + "\n"
                + "JOB KEYWORDS: " + first(keywords, 12) + "\n"
                + "ROLE: " + experience.get("role") + " at " + experience.get("company") + "\n"
                + "ORIGINAL DESCRIPTION: " + Values.str(experience.get("description")) + "\n"
                + "EXPERIENCE KEYWORDS: " + String.join(", ", Values.stringList(experience.get("keywords"))) + "\n"
                + "Return only one sentence.";
        String result;
        try {
            result = ask(prompt, 120);
        } catch (AiClientException error) {
            result = "";
        }
        String sentence = result.split("\n", 2)[0].strip().replaceAll("^[\"']+|[\"']+$", "");
        if (sentence.length() < 20) {
            return Values.truncate(Values.normalizeSpace(experience.get("description")), 220);
        }
        return Values.truncate(sentence, 240);
    }

    private List<String> rewriteProject(Map<String, Object> project, Map<String, Object> job, List<String> keywords) {
        String prompt = "Rewrite these project bullets for a CV.\n"
                + "Return exactly 2 compact bullets, one per line, each starting with '- '.\n"
                + "Do not invent technologies.\n"
                + "JOB TITLE: " + job.get("title") + "\n"
                + "JOB KEYWORDS: " + first(keywords, 12) + "\n"
                + "PROJECT: " + project.get("name") + "\n"
                + "ORIGINAL DESCRIPTION: " + Values.str(project.get("description")) + "\n"
                + "PROJECT KEYWORDS: " + String.join(", ", Values.stringList(project.get("keywords"))) + "\n"
                + "Return only bullets.";
        String result;
        try {
            result = ask(prompt, 160);
        } catch (AiClientException error) {
            result = "";
        }
        List<String> bullets = result.lines()
                .map(line -> line.strip().replaceFirst("^-", "").strip())
                .filter(line -> !line.isEmpty())
                .map(line -> Values.truncate(line, 180))
                .toList();
        if (bullets.size() < 2) {
            String fallback = Values.truncate(Values.normalizeSpace(project.get("description")), 180);
            return List.of(fallback.isEmpty() ? "Built " + project.get("name") + "." : fallback);
        }
        return bullets.stream().limit(3).toList();
    }

    private Map<String, String> letterBody(Map<String, Object> user, Map<String, Object> job, List<String> keywords,
                                           Map<String, Object> experience, Map<String, Object> project) {
        String prompt = "Write a concise cover letter in JSON with keys hook, link, proof_1, proof_2, closing.\n"
                + "Candidate: " + user.get("full_name") + "\n"
                + "Job title: " + job.get("title") + "\n"
                + "Company: " + job.get("company") + "\n"
                + "Job keywords: " + first(keywords, 10) + "\n"
                + "Top experience: " + fields(experience, "role", "company", "description") + "\n"
                + "Top project: " + fields(project, "name", "description") + "\n"
                + "Every value is one or two plain sentences; link is a sentence connecting the company's needs "
                + "to the candidate's work, not a web address.\n"
                + "Use only the facts given above. Never invent numbers, achievements or URLs.\n"
                + "Do not use markdown fences.";
        Map<String, String> body = new LinkedHashMap<>();
        try {
            Map<String, Object> parsed = groq.parseObject(ask(prompt, 500));
            for (String key : List.of("hook", "link", "proof_1", "proof_2", "closing")) {
                body.put(key, Values.normalizeSpace(parsed.get(key)));
            }
            return body;
        } catch (AiClientException error) {
            String company = Values.str(job.get("company"));
            String main = keywords.isEmpty() ? null : keywords.get(0);
            body.put("hook", company + "'s focus on " + (main == null ? "this role" : main) + " stood out to me.");
            body.put("link", "There is a clear parallel with my work on "
                    + (project == null ? "relevant projects" : project.get("name"))
                    + ", where I delivered tangible results.");
            body.put("proof_1", "I bring experience in " + (main == null ? "engineering" : main) + " from "
                    + (experience == null ? "prior work" : experience.get("company")) + ".");
            body.put("proof_2", "My recent work shows practical execution across "
                    + (keywords.size() > 1 ? keywords.get(1) : "core skills") + ".");
            body.put("closing", "I would welcome the opportunity to discuss how I can contribute to "
                    + company + ".");
            return body;
        }
    }

    // ── shaping helpers ─────────────────────────────────────────────────────

    private static String first(List<String> values, int count) {
        return String.join(", ", values.subList(0, Math.min(count, values.size())));
    }

    private static String fields(Map<String, Object> record, String... names) {
        if (record == null) {
            return "";
        }
        List<String> values = new ArrayList<>();
        for (String name : names) {
            values.add(Values.str(record.get(name)));
        }
        return String.join(" ", values);
    }

    private static Map<String, Object> withDates(Map<String, Object> record) {
        Map<String, Object> view = new LinkedHashMap<>(record);
        view.put("date_range", CvContent.dateRange(record.get("start_date"), record.get("end_date")));
        return view;
    }

    private static Set<String> lowerTags(Map<String, Object> job) {
        Set<String> tags = new HashSet<>();
        Values.stringList(job.get("tags")).forEach(tag -> tags.add(tag.toLowerCase(Locale.ROOT)));
        return tags;
    }

    private static Map<String, Object> profile(Map<String, Object> user) {
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("full_name", Values.str(user.get("full_name")));
        profile.put("email", Values.str(user.get("email")));
        profile.put("target_role", Values.str(user.get("raw_job_title")));
        profile.put("bio", Values.str(user.get("bio")));
        profile.put("phone", Values.str(user.get("phone_number")));
        profile.put("linkedin", Values.str(user.get("linkedin_url")));
        profile.put("github", Values.str(user.get("github_url")));
        return profile;
    }

    private static Map<String, Object> jobSnapshot(Map<String, Object> job, List<String> keywords) {
        Map<String, Object> snapshot = new LinkedHashMap<>(job);
        snapshot.remove("description");
        snapshot.put("keywords", keywords);
        return snapshot;
    }

    private static Map<String, Object> generationParams(
            String type, Map<String, Object> job, List<String> keywords,
            List<Map<String, Object>> experiences, List<Map<String, Object>> projects) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("doc_type", type);
        params.put("job_keywords", keywords);
        params.put("job_tags", job.get("tags"));
        params.put("selected_experience_ids", ids(experiences, "experience_id"));
        params.put("selected_project_ids", ids(projects, "project_id"));
        params.put("template_name", TEMPLATE_NAME);
        return params;
    }

    private static List<String> ids(List<Map<String, Object>> records, String key) {
        return records.stream().map(record -> Values.str(record.get(key))).toList();
    }

    private static List<String> excluded(List<Map<String, Object>> all, List<Map<String, Object>> chosen, String key) {
        Set<String> selected = new HashSet<>(ids(chosen, key));
        return ids(all, key).stream().filter(id -> !selected.contains(id)).toList();
    }
}
