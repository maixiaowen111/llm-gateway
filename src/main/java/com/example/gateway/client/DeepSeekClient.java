package com.example.gateway.client;

import com.example.gateway.dto.ChatCompletionRequest;
import com.example.gateway.dto.ChatCompletionResponse;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * DeepSeek 客户端。承载 G1 的 1b（直连上游）与 1c（协议归一）。
 *
 * <p><b>1b 的知识点</b>：WebClient 发出请求后当前线程不会停下来等，返回 {@code Mono} 是"说明书"。
 *
 * <p><b>1c 的知识点</b>：本类的入参和出参都是<b>网关自己的契约</b>
 * （{@link ChatCompletionRequest} / {@link ChatCompletionResponse}），不是上游的。
 * 上游的 JsonNode 只在本类内部的 {@code normalize()} 里出现，<b>一步都不往外漏</b>。
 * 这就是"把厂商差异挡在网关内部"的字面含义。
 */
@Component
public class DeepSeekClient {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekClient.class);

    private final WebClient webClient;
    private final String apiKey;
    private final String defaultModel;

    public DeepSeekClient(WebClient.Builder builder,
                          @Value("${deepseek.base-url}") String baseUrl,
                          @Value("${deepseek.api-key}") String apiKey,
                          @Value("${deepseek.model}") String defaultModel) {
        this.webClient = builder.baseUrl(baseUrl).build();
        this.apiKey = apiKey;
        this.defaultModel = defaultModel;
    }

    /**
     * 1b 的便捷入口：一句话进，一句话出。保留它是为了让 1b 的验收物还能复现。
     */
    public Mono<String> chat(String prompt) {
        return chatCompletion(new ChatCompletionRequest(
                null,
                List.of(new ChatCompletionRequest.Message("user", prompt)),
                false))
                .map(resp -> resp.choices().get(0).message().content());
    }

    /**
     * 1c 的主入口：吃网关自己的契约，吐网关自己的契约。
     *
     * @param request 客户端（项目一 Agent / OpenAI SDK）发来的请求
     */
    public Mono<ChatCompletionResponse> chatCompletion(ChatCompletionRequest request) {
        // 客户端没指定模型就用网关的默认值 —— 网关该有的兜底行为
        String model = StringUtils.hasText(request.model()) ? request.model() : defaultModel;

        // 把「我们的契约」翻译成「DeepSeek 的请求体」。
        // 现在是同一个结构所以看着像白写；等 G3 接一家协议不同的厂商，这一段的必要性就出来了。
        Map<String, Object> upstreamBody = Map.of(
                "model", model,
                "messages", request.messages().stream()
                        .map(m -> Map.of("role", m.role(), "content", m.content()))
                        .toList(),
                // 1c 只做非流式，流式是 G2
                "stream", false
        );

        return webClient.post()
                .uri("/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(upstreamBody)
                .retrieve()
                .bodyToMono(JsonNode.class)
                // 上游原始长什么样，留着给排错用。注意它只活在日志里，不出本方法
                .doOnNext(raw -> log.info("[上游原始响应] {}", raw))
                .map(raw -> normalize(raw, model));
    }

    /**
     * <b>1c 的核心：归一。</b>把上游的原始响应映射成网关对外的契约。
     *
     * <p>这里做了两件在 1b 里做不到的事：
     * <ol>
     *   <li><b>字段由我们决定。</b>上游少给 {@code object} 字段，我们照样吐得出来；
     *       上游给的是 camelCase，出口照样是 snake_case。</li>
     *   <li><b>{@code model} 报"客户端请求的那个"，不报"上游实际用的那个"。</b>
     *       实测发现请求 {@code deepseek-chat} 时上游返回的是 {@code deepseek-flash}。
     *       透传的话客户端会看到一个自己没请求过的模型名；归一后客户端看到的始终是自己要的。
     *       厂商偷偷换模型是网关内部的事。</li>
     * </ol>
     */
    private ChatCompletionResponse normalize(JsonNode raw, String requestedModel) {
        JsonNode choice = raw.path("choices").path(0);
        JsonNode usage = raw.path("usage");

        return new ChatCompletionResponse(
                raw.path("id").asText(),
                "chat.completion",
                raw.path("created").asLong(),
                // ← 注意：requestedModel，不是 raw.path("model")。这是 1c 最该记住的一行
                requestedModel,
                List.of(new ChatCompletionResponse.Choice(
                        0,
                        new ChatCompletionResponse.Message(
                                choice.path("message").path("role").asText("assistant"),
                                choice.path("message").path("content").asText()),
                        choice.path("finish_reason").asText())),
                new ChatCompletionResponse.Usage(
                        usage.path("prompt_tokens").asInt(),
                        usage.path("completion_tokens").asInt(),
                        usage.path("total_tokens").asInt())
        );
    }
}
