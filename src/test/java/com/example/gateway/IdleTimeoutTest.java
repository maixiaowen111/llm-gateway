package com.example.gateway;

import com.example.gateway.dto.ChatCompletionChunk;
import com.example.gateway.dto.ChatCompletionRequest;
import com.example.gateway.exception.UpstreamIdleTimeoutException;
import com.example.gateway.provider.OpenAiCompatibleProvider;
import com.example.gateway.provider.ProviderRegistry;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>G2-2d 的验收物：补上 1d 留下的坑 —— 流式响应的"中途停滞"必须被发现。</b>
 *
 * <p><b>1d 留下的坑长什么样</b>：{@code responseTimeout} 量的是「请求发出 → 收到响应头」。
 * 非流式下响应头就是全部内容，所以它够用。但 SSE 流式的响应头<b>几十毫秒就回来了</b>，
 * 之后内容可能吐几分钟 —— 这个阶段 {@code responseTimeout} <b>已经"成功"了</b>，
 * 中途卡死它一点都管不到。结果是：客户端永远等下一个字，网关上留一条永不结束的连接。
 *
 * <p><b>两个测试是一对，缺一不可</b>
 * <ul>
 *   <li><b>停滞要被发现</b> —— 块与块间隔超过阈值时必须报错。</li>
 *   <li><b>健康的长回复不能被误杀</b> —— 这条更隐蔽，也更危险。
 *       如果把超时值设得跟连接超时一样小（3 秒），模型思考时正常停顿几秒就会被掐断，
 *       用户看到的是"回复说到一半突然没了"。<b>这比超时不触发还严重。</b></li>
 * </ul>
 * 只测前者的话，把阈值写成 1 毫秒也能过测试 —— 那是个灾难性的配置。
 *
 * <p><b>为什么这个测试直接调 client，不经过 HTTP</b>：
 * 2c 的测试必须走 HTTP（要真断连）；但 2d 验的是"网关<b>内部</b>判定停滞"这个逻辑。
 * 而且走 HTTP 反而验不准：超时发生时响应头<b>早就发出去了</b>，状态码改不了，
 * Spring 只能粗暴关掉连接，客户端收到的是一个笼统的"连接被中断"，
 * <b>根本看不出是我们的空闲超时还是网络抖动</b>。直接在 client 层断言异常类型，
 * 精确、稳定、且验的正是我们写的那行代码。
 */
@SpringBootTest
@DisplayName("G2-2d 逐块空闲超时")
class IdleTimeoutTest {

    private static final WireMockServer UPSTREAM = startUpstream();

    private static WireMockServer startUpstream() {
        WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        return server;
    }

    /** 故意调得很小，好在测试里几秒内触发 —— 生产值是 20000ms（见 application.yml） */
    private static final long IDLE_TIMEOUT_MS = 400;

    private static final int CHUNK_COUNT = 8;

    /** 每块字节数严格相等，理由同 2a 测试（WireMock 按字节等分滴流） */
    private static final String UPSTREAM_SSE = buildUpstreamSse();

    private static String buildUpstreamSse() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < CHUNK_COUNT; i++) {
            sb.append("data: {\"id\":\"c\",\"object\":\"chat.completion.chunk\",\"created\":1,")
                    .append("\"model\":\"deepseek-chat\",\"choices\":[{\"index\":0,")
                    .append("\"delta\":{\"content\":\"x\"},\"finish_reason\":null}]}\n\n");
        }
        return sb.toString();
    }

    @DynamicPropertySource
    static void pointAtFakeUpstreamWithTinyIdleTimeout(DynamicPropertyRegistry registry) {
        // ★ G3b：配置键跟着结构改了 —— 渠道参数现在按渠道名归到 channels 下面。
        //   这个改动是"必要的疼"：结构变了，指向它的配置键必须跟着变，
        //   否则测试会连到真实的 DeepSeek 上（然后因为密钥无效而失败，
        //   报错信息还完全看不出真正的原因）。
        registry.add("gateway.providers.channels.deepseek.base-url",
                () -> "http://localhost:" + UPSTREAM.port());
        registry.add("gateway.providers.channels.deepseek.api-key",
                () -> "test-key-not-a-real-secret");
        registry.add("gateway.upstream.idle-timeout-ms", () -> String.valueOf(IDLE_TIMEOUT_MS));
    }

    @Autowired
    private ProviderRegistry providerRegistry;

    /**
     * <b>G3b：不能再 {@code @Autowired OpenAiCompatibleProvider} 了。</b>
     *
     * <p>因为现在<b>渠道不是 Bean</b> —— 它们是 {@code ProviderConfig} 在运行时
     * 按配置造出来、塞进 {@link ProviderRegistry} 的对象。
     * 容器里没有一个叫"那个 deepseek 渠道"的 Bean 可以直接注入。
     *
     * <p>所以先从注册表按名字取，再向下转型到具体实现 —— 因为
     * {@code chatCompletionStreamRaw} <b>不在 SPI 上</b>（它是 2a 的验收物，
     * 吐的是上游原始行，不是网关对外的承诺）。
     *
     * <p><b>这个"不得不转型"的别扭是设计在正常工作，不是缺陷。</b>
     * 它精确地标示出了 SPI 的边界：注册表给你的是"渠道"这个抽象，
     * 而"上游原始行长什么样"是抽象之外的东西，想碰它就得自己承担转型风险。
     */
    private OpenAiCompatibleProvider client;

    @BeforeEach
    void setUp() {
        UPSTREAM.resetAll();
        this.client = (OpenAiCompatibleProvider) providerRegistry.resolve("deepseek");
    }

    @AfterAll
    static void stopUpstream() {
        UPSTREAM.stop();
    }

    @Test
    @DisplayName("上游中途卡住 → 报 UpstreamIdleTimeoutException，而不是无限等下去")
    void stalledUpstreamIsDetected() {
        // 8 块摊在 8 秒里吐 —— 每块间隔约 1000ms，远超 400ms 的阈值
        stubDribbling(8000);

        List<ChatCompletionChunk> received = new ArrayList<>();
        AtomicReference<Throwable> error = new AtomicReference<>();

        client.chatCompletionStream(streamRequest())
                .doOnNext(received::add)
                // 用 onErrorResume 把异常<b>原样</b>接住再断言类型。
                // 比断言"block() 抛了什么"精确 —— 中间层包装过的异常也能一眼看清。
                .onErrorResume(e -> {
                    error.set(e);
                    return Flux.empty();
                })
                .collectList()
                .block();

        assertThat(error.get())
                .as("上游块与块之间隔了约 1000ms（阈值 %d ms），必须判定为停滞并中断", IDLE_TIMEOUT_MS)
                .isInstanceOf(UpstreamIdleTimeoutException.class);

        assertThat(((UpstreamIdleTimeoutException) error.get()).idleTimeoutMs())
                .as("异常里要带上触发它的阈值，排错时才知道该调哪个配置")
                .isEqualTo(IDLE_TIMEOUT_MS);
    }

    @Test
    @DisplayName("块持续到达时不得误杀 —— 长回复中间停顿几秒是正常的")
    void steadyStreamIsNotKilled() {
        // 8 块摊在 800ms 里 —— 每块间隔约 100ms，远小于 400ms 阈值
        stubDribbling(800);

        List<ChatCompletionChunk> received = new ArrayList<>();
        AtomicReference<Throwable> error = new AtomicReference<>();

        client.chatCompletionStream(streamRequest())
                .doOnNext(received::add)
                .onErrorResume(e -> {
                    error.set(e);
                    return Flux.empty();
                })
                .collectList()
                .block();

        assertThat(error.get())
                .as("块一直在到，空闲超时不该触发 —— 否则健康的长回复会被拦腰掐断")
                .isNull();

        assertThat(received)
                .as("8 块应当一块不少地全部透出去")
                .hasSize(CHUNK_COUNT);
    }

    private ChatCompletionRequest streamRequest() {
        return new ChatCompletionRequest(
                null,
                List.of(new ChatCompletionRequest.Message("user", "你好")),
                true,
                null);   // G3b：不指定渠道，走默认
    }

    private void stubDribbling(int totalDribbleMs) {
        UPSTREAM.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/event-stream")
                        .withBody(UPSTREAM_SSE)
                        .withChunkedDribbleDelay(CHUNK_COUNT, totalDribbleMs)));
    }
}
