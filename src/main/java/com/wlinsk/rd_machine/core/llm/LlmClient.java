package com.wlinsk.rd_machine.core.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wlinsk.rd_machine.basic.config.AiLlmProperties;
import com.wlinsk.rd_machine.basic.model.bo.LlmMessage;
import com.wlinsk.rd_machine.basic.model.bo.LlmUsage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

@Slf4j
@Component
public class LlmClient {

    private static final String DATA_PREFIX = "data:";
    private static final String DONE_MARKER = "[DONE]";

    private final LlmService llmService;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String baseUrl;
    private final HttpClient httpClient;

    public LlmClient(AiLlmProperties properties, LlmService llmService, ObjectMapper objectMapper) {
        this.llmService = llmService;
        this.objectMapper = objectMapper;
        this.apiKey = properties.getApiKey();
        this.baseUrl = stripTrailingSlash(properties.getBaseUrl());
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    public void streamChatCompletion(List<LlmMessage> messages, LlmDeltaListener listener, BooleanSupplier cancelled) {
        HttpRequest request = buildRequest(messages);
        try {
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() / 100 != 2) {
                String errorBody = readErrorBody(response.body());
                throw new IllegalStateException("LLM request failed: HTTP " + response.statusCode() + " " + errorBody);
            }
            JsonNode usageNode = consumeStream(response.body(), listener, cancelled);
            if (!cancelled.getAsBoolean() && !Thread.currentThread().isInterrupted()) {
                listener.onComplete(toLlmUsage(usageNode));
            }
        } catch (CancellationException e) {
            log.warn("Session closed: ", e);
            Thread.interrupted();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("streamChatCompletion interrupted: ", e);
        } catch (Exception exception) {
            log.warn("streamChatCompletion error: ", exception);
            if (!cancelled.getAsBoolean()) {
                listener.onError(exception);
                throw new RuntimeException(exception);
            }
        }
    }

    private JsonNode consumeStream(InputStream body, LlmDeltaListener listener, BooleanSupplier cancelled) throws Exception {
        JsonNode usageNode = null;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                    throw new CancellationException("Session closed");
                }
                if (!line.startsWith(DATA_PREFIX)) {
                    continue;
                }
                String data = line.substring(DATA_PREFIX.length()).trim();
                if (data.isEmpty()) {
                    continue;
                }
                if (DONE_MARKER.equals(data)) {
                    break;
                }
                JsonNode node = objectMapper.readTree(data);
                JsonNode usage = node.get("usage");
                if (usage != null && !usage.isNull()) {
                    usageNode = usage;
                }
                JsonNode choices = node.get("choices");
                if (choices == null) {
                    continue;
                }
                for (JsonNode choice : choices) {
                    JsonNode content = choice.path("delta").path("content");
                    if (!content.isTextual()) {
                        continue;
                    }
                    String delta = content.asText();
                    if (delta.isBlank()) {
                        continue;
                    }
                    if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                        throw new CancellationException("Session closed");
                    }
                    listener.onDelta(delta);
                }
            }
        }
        return usageNode;
    }

    private HttpRequest buildRequest(List<LlmMessage> messages) {
        String payload = buildPayload(messages);
        return HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .timeout(Duration.ofMinutes(2))
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();
    }

    private String buildPayload(List<LlmMessage> messages) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", llmService.getModel());
        root.put("stream", true);
        root.set("stream_options", objectMapper.createObjectNode().put("include_usage", true));
        ArrayNode messageArray = root.putArray("messages");
        for (LlmMessage message : messages) {
            if (!isSupportedRole(message.role())) {
                throw new IllegalArgumentException("Unsupported role: " + message.role());
            }
            ObjectNode messageNode = messageArray.addObject();
            messageNode.put("role", message.role());
            messageNode.put("content", message.content());
        }
        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize LLM request payload", e);
        }
    }

    private boolean isSupportedRole(String role) {
        return "system".equals(role) || "user".equals(role) || "assistant".equals(role);
    }

    private LlmUsage toLlmUsage(JsonNode usage) {
        if (usage == null || usage.isNull()) {
            log.warn("LLM stream completed without a usage payload; defaulting token usage to 0. "
                    + "Verify the provider honors stream_options.include_usage.");
            return new LlmUsage(0L, 0L, 0L, 0L);
        }
        long promptTokens = usage.path("prompt_tokens").asLong(0L);
        long completionTokens = usage.path("completion_tokens").asLong(0L);
        long totalTokens = usage.path("total_tokens").asLong(0L);
        long cachedTokens = usage.path("prompt_tokens_details").path("cached_tokens").asLong(0L);
        return new LlmUsage(promptTokens, completionTokens, totalTokens, cachedTokens);
    }

    private String readErrorBody(InputStream body) {
        try (InputStream stream = body) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "<unreadable response body: " + e.getMessage() + ">";
        }
    }

    private static String stripTrailingSlash(String url) {
        if (url == null) {
            return null;
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
