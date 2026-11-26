package com.jobfinder.service;

import com.jobfinder.util.Values;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Suggests canonical tags from a job title and bio using synonym and stem matching. */
@Component
public class TagRecommender {

    private static final Map<String, List<String>> SYNONYMS = new LinkedHashMap<>();

    static {
        add("frontend", "front end", "front-end", "ui", "web", "web app");
        add("backend", "back end", "back-end", "server", "api");
        add("fullstack", "full stack", "full-stack");
        add("mobile", "mobile", "mobile dev", "mobile development");
        add("embedded-systems", "embedded", "firmware", "microcontroller");
        add("systems-programming", "systems programming", "low level", "kernel", "os");
        add("devops", "devops", "sre", "site reliability", "infrastructure");
        add("cloud", "cloud", "aws", "gcp", "azure");
        add("security", "security", "cybersecurity", "infosec", "appsec");
        add("testing", "testing", "qa", "quality assurance");
        add("software development", "software development", "software engineering", "software engineer",
                "developer", "engineering");
        add("ai-ml", "ai", "machine learning", "ml", "deep learning", "dl", "nlp", "computer vision", "cv",
                "llm", "gen ai", "generative ai");
        add("data-science", "data science", "analytics", "statistics", "modelling", "modeling");
        add("data-engineering", "data engineering", "data engineer", "etl", "pipeline", "data pipelines",
                "warehouse", "data warehouse");
        add("research", "research", "r&d", "researcher", "scientist");
        add("automation", "automation", "scripting", "rpa", "workflow");
        add("product-management", "product manager", "product management", "pm", "product owner", "product lead");
        add("consulting", "consulting", "consultant", "advisory");
        add("technical-writing", "technical writing", "documentation", "tech writer");
        add("agile", "agile", "scrum", "kanban");
        add("ui-ux", "ui/ux", "ui ux", "user experience", "ux", "ui", "interaction design");
        add("product-design", "product design", "ux design", "design lead");
        add("graphic-design", "graphic design", "visual design", "branding", "brand design");
        add("motion-design", "motion design", "animation", "motion graphics", "vfx");
        add("video-editing", "video editing", "editor", "post production", "color grading", "colour grading");
        add("content-creation", "content creation", "copywriting", "copywriter", "social media", "blogging");
        add("photography", "photography", "photographer");
        add("3d-modelling", "3d", "3d modeling", "3d modelling", "cad", "rendering", "sculpting");
        add("game-development", "game development", "game dev", "gaming");
        add("ar-vr", "ar", "vr", "xr", "augmented reality", "virtual reality", "spatial computing");
        add("robotics", "robotics", "ros", "mechatronics");
        add("blockchain", "blockchain", "web3", "smart contract", "crypto", "defi");
        add("ios", "ios", "iphone", "swift");
        add("android", "android", "kotlin");
        add("electronics", "electronics", "pcb", "fpga", "circuit design");
        add("biotech", "biotech", "bioinformatics", "computational biology");
        add("fintech", "fintech", "payments", "trading", "regtech");
        add("sales-growth", "sales", "growth", "biz dev", "business development", "gtm", "go to market",
                "marketing");
        add("operations", "operations", "ops", "supply chain", "logistics");
        add("hr-people", "hr", "people ops", "recruiting", "talent", "human resources", "l&d",
                "learning and development");
        add("legal-compliance", "legal", "compliance", "regulatory", "risk", "policy");
    }

    private static void add(String tag, String... synonyms) {
        SYNONYMS.put(tag, List.of(synonyms));
    }

    /** Tokens, their stems and the two/three word phrases of a text. */
    private record Terms(Set<String> tokens, Set<String> stems, Set<String> phrases) {

        boolean matches(String phrase) {
            String normalized = normalize(phrase);
            if (normalized.isEmpty()) {
                return false;
            }
            if (phrases.contains(normalized) || tokens.contains(normalized)) {
                return true;
            }
            for (String token : normalized.split(" ")) {
                if (!stems.contains(stem(token))) {
                    return false;
                }
            }
            return true;
        }
    }

    public Set<String> recommend(String jobTitle, String bio, List<String> tagNames) {
        Terms terms = extract(Values.str(jobTitle) + " " + Values.str(bio));
        Set<String> matched = new HashSet<>();
        for (String tag : tagNames) {
            if (terms.matches(tag.replace('-', ' '))
                    || SYNONYMS.getOrDefault(tag, List.of()).stream().anyMatch(terms::matches)) {
                matched.add(tag);
            }
        }
        return matched;
    }

    private static Terms extract(String text) {
        Set<String> tokenSet = new HashSet<>();
        Set<String> stems = new HashSet<>();
        Set<String> phrases = new HashSet<>();
        String normalized = normalize(text);
        if (!normalized.isEmpty()) {
            List<String> tokens = new ArrayList<>(List.of(normalized.split(" ")));
            for (String token : tokens) {
                tokenSet.add(token);
                stems.add(stem(token));
            }
            for (int size = 2; size <= 3; size++) {
                for (int i = 0; i + size <= tokens.size(); i++) {
                    phrases.add(String.join(" ", tokens.subList(i, i + size)));
                }
            }
        }
        return new Terms(tokenSet, stems, phrases);
    }

    private static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").strip();
    }

    static String stem(String token) {
        if (token.length() <= 3) {
            return token;
        }
        if (token.endsWith("ies") && token.length() > 4) {
            return token.substring(0, token.length() - 3) + "y";
        }
        for (String suffix : List.of("ing", "ed", "er", "or", "ly", "es")) {
            if (token.length() > suffix.length() + 2 && token.endsWith(suffix)) {
                return token.substring(0, token.length() - suffix.length());
            }
        }
        if (token.endsWith("s") && token.length() > 4) {
            return token.substring(0, token.length() - 1);
        }
        return token;
    }
}
