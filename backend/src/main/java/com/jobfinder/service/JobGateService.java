package com.jobfinder.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobfinder.client.AiClientException;
import com.jobfinder.client.GroqClient;
import com.jobfinder.client.HuggingFaceClient;
import com.jobfinder.config.AppSettings;
import com.jobfinder.util.Values;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * AI evaluation of one job against the user's profile. Groq is tried first, then HuggingFace,
 * and finally a keyword heuristic so that a search never fails because a model is unavailable.
 */
@Service
public class JobGateService {

    /** Outcome of the evaluation. */
    public record Verdict(
            boolean passes, String briefDescription, List<String> techStack, String whyFit,
            List<String> matchingExperiences, List<String> matchingProjects, String provider) {
    }

    private static final String SCHEMA = """
            {"evaluation": {"passes_filters": false, "rejection_reason": null}, \
            "extracted_job_data": {"job_title": "string", "company_name": "string", "location": "string", \
            "hiring_type": "string", "compensation": "string", "brief_description": "string", "tech_stack": [], \
            "work_model": "string"}, \
            "fit_analysis": {"matching_experiences": [], "matching_projects": [], "why_fit": "string"}}""";

    private static final String DEFAULT_WHY = "This role aligns with your background and keyword overlap.";

    private final GroqClient groq;
    private final HuggingFaceClient huggingFace;
    private final AppSettings settings;
    private final ObjectMapper mapper;

    public JobGateService(GroqClient groq, HuggingFaceClient huggingFace, AppSettings settings, ObjectMapper mapper) {
        this.groq = groq;
        this.huggingFace = huggingFace;
        this.settings = settings;
        this.mapper = mapper;
    }

    public Verdict evaluate(Map<String, Object> profile, Map<String, Object> constraints, Map<String, Object> job) {
        Verdict fallback = heuristic(Values.stringList(profile.get("skills_detected")), job);
        String prompt = prompt(Map.of("profile", profile, "constraints", constraints), job);
        try {
            String answer = groq.complete(prompt, settings.groqModel(), 0.0, null, true,
                    Duration.ofSeconds(30));
            return validate(groq.parseObject(answer), fallback, "groq");
        } catch (AiClientException groqFailure) {
            try {
                return validate(huggingFace.completeJson(prompt), fallback, "ai");
            } catch (AiClientException huggingFaceFailure) {
                return fallback;
            }
        }
    }

    private String prompt(Map<String, Object> sessionTruth, Map<String, Object> job) {
        try {
            return "You are an expert job-filtering engine and structured data extractor.\n"
                    + "Evaluate the raw job against the user's constraints from SESSION_TRUTH.\n"
                    + "Step 1: Strict filtering. Reject if the description explicitly blocks the user's region, "
                    + "pay floor, experience level, or education level.\n"
                    + "Step 2: If the job passes, extract concise UI-ready fields.\n"
                    + "Rules:\n"
                    + "- Return ONLY valid JSON matching JSON_SCHEMA.\n"
                    + "- Keep brief_description to 1-2 concise sentences.\n"
                    + "- If rejected: set extracted_job_data and fit_analysis to null.\n"
                    + "- Prefer concrete rejection reasons: region restriction, seniority mismatch, pay mismatch, "
                    + "education requirement.\n"
                    + "JSON_SCHEMA:\n" + SCHEMA + "\n"
                    + "SESSION_TRUTH:\n" + mapper.writeValueAsString(sessionTruth) + "\n"
                    + "JOB:\n" + mapper.writeValueAsString(compact(job)) + "\n";
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Unable to serialise job", error);
        }
    }

    /** Only the fields the model needs, with the description capped, to stay under Groq's token-per-minute limit. */
    private static Map<String, Object> compact(Map<String, Object> job) {
        Map<String, Object> slim = new java.util.LinkedHashMap<>();
        for (String field : List.of("title", "company", "location", "employment_type", "work_model", "salary_range")) {
            slim.put(field, job.get(field));
        }
        slim.put("description", Values.truncate(Values.normalizeSpace(job.get("description")), 1800));
        return slim;
    }

    private static Verdict heuristic(List<String> skills, Map<String, Object> job) {
        String description = Values.normalizeSpace(job.get("description"));
        String brief = description.length() > 220 ? description.substring(0, 220) + "..." : description;
        String lowered = description.toLowerCase(Locale.ROOT);
        List<String> matched = skills.stream()
                .filter(skill -> lowered.contains(skill.toLowerCase(Locale.ROOT))).limit(6).toList();
        return new Verdict(true, brief.isEmpty() ? "No description provided." : brief, matched, DEFAULT_WHY,
                slice(matched, 0, 3), slice(matched, 3, 6), "algorithmic");
    }

    private static Verdict validate(Map<String, Object> payload, Verdict fallback, String provider) {
        Map<?, ?> evaluation = object(payload.get("evaluation"));
        if (evaluation == null || !Boolean.TRUE.equals(evaluation.get("passes_filters"))) {
            return new Verdict(false, null, List.of(), null, List.of(), List.of(), provider);
        }
        Map<?, ?> extracted = object(payload.get("extracted_job_data"));
        Map<?, ?> fit = object(payload.get("fit_analysis"));

        String brief = fallback.briefDescription();
        List<String> stack = fallback.techStack();
        if (extracted != null) {
            brief = Values.firstNonBlank(Values.normalizeSpace(extracted.get("brief_description")), brief);
            List<String> given = strings(extracted.get("tech_stack"));
            stack = given.isEmpty() ? stack : given;
        }
        if (fit == null) {
            return new Verdict(true, brief, stack, fallback.whyFit(),
                    fallback.matchingExperiences(), fallback.matchingProjects(), provider);
        }
        return new Verdict(true, brief, stack,
                Values.firstNonBlank(Values.normalizeSpace(fit.get("why_fit")), fallback.whyFit()),
                strings(fit.get("matching_experiences")), strings(fit.get("matching_projects")), provider);
    }

    private static Map<?, ?> object(Object value) {
        return value instanceof Map<?, ?> map ? map : null;
    }

    /** Up to six non-empty, whitespace-normalised strings. */
    private static List<String> strings(Object value) {
        List<String> result = new ArrayList<>();
        if (value instanceof List<?> list) {
            list.stream().map(Values::normalizeSpace).filter(s -> !s.isEmpty()).limit(6).forEach(result::add);
        }
        return result;
    }

    private static List<String> slice(List<String> list, int from, int to) {
        return list.subList(Math.min(from, list.size()), Math.min(to, list.size()));
    }
}
