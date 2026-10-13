package com.example.gateway.provider;

import com.example.gateway.dto.ChatCompletionChunk;
import com.example.gateway.dto.ChatCompletionRequest;
import com.example.gateway.dto.ChatCompletionResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * <b>上游渠道的 SPI（服务提供者接口）。G3-3a 的产物。</b>
 *
 * <p><b>它解决什么问题 —— 先看没有它会怎样</b>：
 * 在 3a 之前，{@code ChatController} 直接依赖具体类 {@code DeepSeekClient}。
 * 想接第二家厂商，只有两条路：
 * <ol>
 *   <li>把代码复制一份改成 {@code OpenAiClient}，然后 Controller 里写 {@code if (是DeepSeek) ... else ...}
 *       —— 接第三家就变成三分支，第四家四分支。<b>这不是网关，是 if-else 堆。</b></li>
 *   <li>把 DeepSeek 的所有逻辑塞进一个类里，用 if 区分 —— 一个类里住着 N 家厂商的协议差异，
 *       谁都不敢改。</li>
 * </ol>
 * 两条路都会导致同一个后果：<b>每加一家厂商，改动都会扩散到已有代码里。</b>
 *
 * <p><b>SPI 的价值一句话</b>：<b>加第三家的时候，不用改已有代码。</b>
 * 新写一个 {@code XxxProvider} 实现本接口、注册成 Bean，<b>就接完了</b> ——
 * {@code ChatController} 一行不动。
 * （这其实是开闭原则，但用具体场景讲出来比背定义有用得多。）
 *
 * <p><b>★ 最重要的设计约束：本接口里不能出现任何厂商的痕迹 ★</b>
 * 入参出参都是网关自己的契约（{@link ChatCompletionRequest} /
 * {@link ChatCompletionResponse} / {@link ChatCompletionChunk}）。
 * <b>一旦这里出现了 {@code JsonNode}、或方法名里带了某家厂商的名字，
 * 这层抽象就漏了</b> —— 实现类会把厂商细节带到调用方。
 *
 * <p><b>为什么只有两个方法</b>：网关对外只承诺两件事 —— 非流式一次成型、
 * 流式逐块交付。至于"上游吐的原始行长什么样"，那是<b>实现细节，不是契约</b>，
 * 所以它不该出现在接口上（DeepSeek 的实现里确实留了一个 raw 方法，
 * 但那是给 2a 的验收物用的，接口看不见它）。
 */
public interface Provider {

    /**
     * 渠道标识，用于配置和路由。要求全局唯一。
     *
     * <p><b>为什么不用类的简单名（{@code getClass().getSimpleName()}）</b>：
     * 那样名字就被类名绑死了 —— 改个类名、或者同一个类想注册成两个渠道
     * （比如同一家厂商的两个不同 key），都会出问题。
     * <b>让实现自己声明名字，名字就是配置里写的那个字符串。</b>
     */
    String name();

    /**
     * 非流式补全：吃网关契约，吐网关契约。
     *
     * @return 一个元素 —— 上游全部写完才有值。调用方<b>不应假设它很快返回</b>。
     */
    Mono<ChatCompletionResponse> chatCompletion(ChatCompletionRequest request);

    /**
     * 流式补全：吃网关契约，吐网关契约的<b>分块</b>。
     *
     * <p><b>⚠️ 实现方必须遵守的两条铁律</b>（否则 SPI 就白抽了）：
     * <ol>
     *   <li><b>真的逐块往下发</b>，不能中间攒齐再发 —— 返回 {@code Flux} 不等于流式，
     *       攒齐了再发也是 {@code Flux}，但用户那边的打字机效果就没了。</li>
     *   <li><b>不吐 {@code [DONE]} 哨兵</b> —— 那是协议尾巴，由出口（Controller）负责补。
     *       "谁负责协议尾巴，谁补"，实现方只管数据块。</li>
     * </ol>
     *
     * <p>另外，实现方<b>不要在这里做限流/熔断</b> —— 那是 G4/G5 的事，
     * 会包在本接口外面。<b>每一层只管一件事。</b>
     */
    Flux<ChatCompletionChunk> chatCompletionStream(ChatCompletionRequest request);
}
