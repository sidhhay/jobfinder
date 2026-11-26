package com.jobfinder.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobfinder.config.AppSettings;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/** HuggingFace Inference API, used by the job gate when Groq is unavailable. */
@Component
public class HuggingFaceClient {

    private final AppSettings settings;
    private final Http http;
    private final ObjectMapper mapper;
    private final GroqClient jsonParser;

    public HuggingFaceClient(AppSettings settings, Http http, ObjectMapper mapper, GroqClient jsonParser) {
        this.settings = settings;
        this.http = http;
        this.mapper = mapper;
        this.jsonParser = jsonParser;
    }

    public Map<String, Object> completeJson(String prompt) {
        String token = settings.huggingFaceToken();
        if (token == null) {
            throw new AiClientException("HF_TOKEN is missing.");
        }
        try {
            String body = mapper.writeValueAsString(Map.of(
                    "inputs", prompt,
                    "parameters", Map.of("max_new_tokens", 220, "temperature", 0.0, "return_full_text", false)));
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create("https://api-inference.huggingface.co/models/" + settings.huggingFaceModel()))
                    .timeout(Duration.ofSeconds(40))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + token)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = http.send(request);
            if (response.statusCode() >= 400) {
                throw new AiClientException("hf_http_" + response.statusCode());
            }
            JsonNode raw = mapper.readTree(response.body());
            JsonNode text = raw.isArray() ? raw.path(0).path("generated_text") : raw.path("generated_text");
            if (!text.isTextual()) {
                throw new AiClientException("hf_invalid_response");
            }
            return jsonParser.parseObject(text.asText());
        } catch (IOException error) {
            throw new AiClientException("hf_network_error:" + error.getMessage(), error);
        }
    }
}
