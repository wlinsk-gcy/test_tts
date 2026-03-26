package com.wlinsk.rd_machine.llm;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.http.StreamResponse;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.wlinsk.rd_machine.config.AiLlmProperties;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
public class BailianLlmClient {

    private final AiLlmProperties properties;
    private final BailianLlmService llmService;

    public BailianLlmClient(AiLlmProperties properties, BailianLlmService llmService) {
        this.properties = properties;
        this.llmService = llmService;
    }

    public void streamChatCompletion(String systemPrompt, String userPrompt, LlmDeltaListener listener) {
        ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
                .model(llmService.getModel())
                .addSystemMessage(systemPrompt)
                .addUserMessage(userPrompt)
                .build();
        OpenAIClient openAIClient = OpenAIOkHttpClient.builder()
                .apiKey(properties.getApiKey())
                .baseUrl(properties.getBaseUrl())
                .timeout(Duration.ofMinutes(2))
                .build();
        try (StreamResponse<ChatCompletionChunk> response = openAIClient.chat().completions().createStreaming(params)) {
            response.stream()
                    .flatMap(chunk -> chunk.choices().stream())
                    .flatMap(choice -> choice.delta().content().stream())
                    .filter(delta -> !delta.isBlank())
                    .forEach(listener::onDelta);
            listener.onComplete();
        } catch (Exception exception) {
            listener.onError(exception);
            throw exception;
        }
    }
}
