package com.fxbrief.analysis.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fxbrief.analysis.config.ClaudeProperties;
import com.fxbrief.common.constants.ErrorCodes;
import com.fxbrief.common.exception.DomainException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Thin client for the Anthropic Messages API. Supports both the simple
 * single-message API and the steering features required by the mega-call
 * narrative generator: explicit temperature and assistant-message
 * prefilling for forcing structured JSON output.
 *
 * The model never estimates or computes price levels — all numeric values
 * are pre-computed in the analysis engine and embedded as plain text in
 * the user prompt (PRD §11.3 step 7, §15 hallucination mitigation).
 *
 * Protected by Resilience4j retry and circuit breaker policies named
 * {@code claude}.
 */
@Slf4j
@Component
public class ClaudeClient {

    public static final String RESILIENCE_NAME = "claude";

    private static final String API_KEY_HEADER = "x-api-key";
    private static final String API_VERSION_HEADER = "anthropic-version";

    private final ClaudeProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public ClaudeClient(ClaudeProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(buildRequestFactory(properties))
                .defaultHeader(API_KEY_HEADER, properties.apiKey())
                .defaultHeader(API_VERSION_HEADER, properties.apiVersion())
                .build();
    }

    private static ClientHttpRequestFactory buildRequestFactory(ClaudeProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(properties.connectTimeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(properties.readTimeoutMs()));
        return factory;
    }

    /**
     * Simple call: single user message, no prefilling, default temperature.
     * Used for the per-pair fallback path.
     */
    public String generateNarrative(String systemPrompt, String userPrompt) {
        return invoke(new ClaudeRequest(systemPrompt, userPrompt, null, null));
    }

    /**
     * Steered call: user message + optional assistant prefill + explicit
     * temperature. Used for the mega-call path which forces JSON output.
     * When {@code assistantPrefill} is non-null, the returned text is the
     * concatenation of the prefill and Claude's continuation, so callers
     * can parse the full string as the original prefilled structure.
     */
    public String generateNarrative(String systemPrompt, String userPrompt,
                                    String assistantPrefill, Double temperature) {
        return invoke(new ClaudeRequest(systemPrompt, userPrompt, assistantPrefill, temperature));
    }

    @Retry(name = RESILIENCE_NAME)
    @CircuitBreaker(name = RESILIENCE_NAME)
    String invoke(ClaudeRequest request) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", properties.model());
        body.put("max_tokens", properties.maxTokens());
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            body.put("system", request.systemPrompt());
        }
        if (request.temperature() != null) {
            body.put("temperature", request.temperature());
        }

        ArrayNode messages = body.putArray("messages");
        ObjectNode userMessage = messages.addObject();
        userMessage.put("role", "user");
        userMessage.put("content", request.userPrompt());

        if (request.assistantPrefill() != null && !request.assistantPrefill().isEmpty()) {
            ObjectNode assistantMessage = messages.addObject();
            assistantMessage.put("role", "assistant");
            assistantMessage.put("content", request.assistantPrefill());
        }

        try {
            String responseBody = restClient.post()
                    .uri("/v1/messages")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(objectMapper.writeValueAsString(body))
                    .retrieve()
                    .body(String.class);

            String continuation = extractText(responseBody);
            return request.assistantPrefill() != null
                    ? request.assistantPrefill() + continuation
                    : continuation;
        } catch (HttpClientErrorException e) {
            log.warn("Claude API client error: status={} body={}",
                    e.getStatusCode(), e.getResponseBodyAsString());
            throw apiFailure("Claude client error " + e.getStatusCode());
        } catch (HttpServerErrorException | ResourceAccessException e) {
            log.warn("Claude API transient failure: {}", e.getMessage());
            throw apiFailure("Claude transient failure: " + e.getMessage());
        } catch (DomainException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Claude API unexpected failure: {}", e.getMessage());
            throw apiFailure("Claude unexpected failure: " + e.getMessage());
        }
    }

    private String extractText(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            throw apiFailure("Claude empty response");
        }
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode content = root.path("content");
            if (!content.isArray() || content.isEmpty()) {
                throw apiFailure("Claude response missing content array");
            }
            StringBuilder builder = new StringBuilder();
            for (JsonNode block : content) {
                if ("text".equals(block.path("type").asText())) {
                    builder.append(block.path("text").asText());
                }
            }
            String text = builder.toString().trim();
            if (text.isEmpty()) {
                throw apiFailure("Claude response contained no text blocks");
            }
            return text;
        } catch (DomainException e) {
            throw e;
        } catch (Exception e) {
            throw apiFailure("Claude response parse error: " + e.getMessage());
        }
    }

    private DomainException apiFailure(String message) {
        return new DomainException(
                ErrorCodes.NARRATIVE_UNAVAILABLE,
                HttpStatus.SERVICE_UNAVAILABLE,
                message);
    }

    public record ClaudeRequest(
            String systemPrompt,
            String userPrompt,
            String assistantPrefill,
            Double temperature
    ) {}
}
