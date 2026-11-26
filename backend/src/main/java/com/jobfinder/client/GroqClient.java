package com.jobfinder.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobfinder.config.AppSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Client for the Groq chat completion API. */
@Component
public class GroqClient {

    private static final URI ENDPOINT = URI.create("https://api.groq.com/openai/v1/chat/completions");
    private static final Logger LOG = LoggerFactory.getLogger(GroqClient.class);
    private static final int REASONING_HEADROOM = 1024;
    private static final Pattern JSON_BLOCK = Pattern.compile("\\{.*}", Pattern.DOTALL);

    private final AppSettings settings;
    private final Http http;
    private final ObjectMapper mapper;

    public GroqClient(AppSettings settings, Http http, ObjectMapper mapper) {
        this.settings = settings;
        this.http = http;
        this.mapper = mapper;
    }

    /**
     * Sends one prompt and returns the model's text answer.
     *
     * @param maxTokens upper bound of the answer, or {@code null} for the provider default
     * @param jsonMode  ask the model to answer with a JSON object
     */
    public String complete(String prompt, String model, double temperature, Integer maxTokens, boolean jsonMode,
                           Duration timeout) {
        String apiKey = settings.groqApiKey();
        if (apiKey == null) {
            throw new AiClientException("GROQ_API_KEY is missing.");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("messages", List.of(Map.of("role", "user", "content", prompt)));
        payload.put("temperature", temperature);
        boolean reasoningModel = model.startsWith("openai/gpt-oss");
        if (reasoningModel) {
            payload.put("reasoning_effort", "low");
        }
        if (maxTokens != null) {
            // Reasoning tokens count against the limit, so such models need extra room.
            payload.put("max_tokens", reasoningModel ? maxTokens + REASONING_HEADROOM : maxTokens);
        }
        if (jsonMode) {
            payload.put("response_format", Map.of("type", "json_object"));
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(ENDPOINT)
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("User-Agent", "jobfinder-backend/1.0")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
                    .build();
            HttpResponse<String> response = http.send(request);
            if (response.statusCode() >= 400) {
                String body = response.body();
                LOG.warn("Groq request for model {} failed with HTTP {}", model, response.statusCode());
                throw new AiClientException("groq_http_" + response.statusCode() + ":"
                        + body.substring(0, Math.min(300, body.length())));
            }
            JsonNode content = mapper.readTree(response.body()).path("choices").path(0)
                    .path("message").path("content");
            if (!content.isTextual()) {
                throw new AiClientException("groq_invalid_response");
            }
            return content.asText().strip();
        } catch (IOException error) {
            throw new AiClientException("groq_network_error:" + error.getMessage(), error);
        }
    }

    /** Parses a model answer into a JSON object, tolerating code fences and surrounding prose. */
    public Map<String, Object> parseObject(String answer) {
        String cleaned = answer.strip();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```\\s*$", "");
        }
        if (!cleaned.startsWith("{")) {
            Matcher block = JSON_BLOCK.matcher(cleaned);
            if (block.find()) {
                cleaned = block.group();
            }
        }
        try {
            Map<String, Object> parsed =
                    mapper.readValue(cleaned, new TypeReference<LinkedHashMap<String, Object>>() { });
            if (parsed == null) {
                throw new AiClientException("empty JSON answer");
            }
            return parsed;
        } catch (JsonProcessingException error) {
            throw new AiClientException(error.getOriginalMessage(), error);
        }
    }
}
