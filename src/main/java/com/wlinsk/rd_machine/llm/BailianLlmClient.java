package com.wlinsk.rd_machine.llm;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.http.StreamResponse;
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionSystemMessageParam;
import com.openai.models.chat.completions.ChatCompletionUserMessageParam;
import com.wlinsk.rd_machine.config.AiLlmProperties;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class BailianLlmClient {

    private final AiLlmProperties properties;
    private final BailianLlmService llmService;

    public BailianLlmClient(AiLlmProperties properties, BailianLlmService llmService) {
        this.properties = properties;
        this.llmService = llmService;
    }

    public void streamChatCompletion(List<LlmMessage> messages, LlmDeltaListener listener) {
        ChatCompletionCreateParams params = buildParams(messages);
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

    ChatCompletionCreateParams buildParams(List<LlmMessage> messages) {
        ChatCompletionCreateParams.Builder builder = ChatCompletionCreateParams.builder()
                .model(llmService.getModel());
        messages.forEach(message -> builder.addMessage(toMessageParam(message)));
        return builder.build();
    }

    private ChatCompletionMessageParam toMessageParam(LlmMessage message) {
        return switch (message.role()) {
            case "system" -> ChatCompletionMessageParam.ofSystem(
                    ChatCompletionSystemMessageParam.builder()
                            .content(ChatCompletionSystemMessageParam.Content.ofText(message.content()))
                            .build()
            );
            case "user" -> ChatCompletionMessageParam.ofUser(
                    ChatCompletionUserMessageParam.builder()
                            .content(ChatCompletionUserMessageParam.Content.ofText(message.content()))
                            .build()
            );
            case "assistant" -> ChatCompletionMessageParam.ofAssistant(
                    ChatCompletionAssistantMessageParam.builder()
                            .content(ChatCompletionAssistantMessageParam.Content.ofText(message.content()))
                            .build()
            );
            default -> throw new IllegalArgumentException("Unsupported role: " + message.role());
        };
    }
}
