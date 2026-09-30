package com.zachary.BI;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class GenAi {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String model;

    public GenAi(RestClient.Builder restClientBuilder,
                 ObjectMapper objectMapper,
                 @Value("${bi.ai.base-url}") String baseUrl,
                 @Value("${bi.ai.api-key:}") String apiKey,
                 @Value("${bi.ai.model}") String model) {
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.model = model;
    }

    public String doChat(String message) {
        if (StringUtils.isBlank(apiKey)) {
            throw new IllegalStateException("DEEPSEEK_API_KEY is not configured");
        }

        ChatRequest request = new ChatRequest(
                model,
                Arrays.asList(new ChatMessage("user", message)),
                1,
                0.95,
                16384,
                false
        );

        String responseBody = restClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> headers.setBearerAuth(apiKey))
                .body(request)
                .retrieve()
                .body(String.class);

        try {
            JsonNode root = objectMapper.readTree(responseBody);
            String content = root.path("choices").path(0).path("message").path("content").asText();
            if (StringUtils.isBlank(content)) {
                throw new IllegalStateException("AI response did not contain message content");
            }
            return content;
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to parse AI response", exception);
        }
    }

    private record ChatMessage(String role, String content) {
    }

    private record ChatRequest(String model, List<ChatMessage> messages, double temperature,
                               double top_p, int max_tokens, boolean stream) {
    }
}
