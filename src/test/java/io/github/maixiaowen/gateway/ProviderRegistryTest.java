package io.github.maixiaowen.gateway;

import io.github.maixiaowen.gateway.dto.ChatCompletionChunk;
import io.github.maixiaowen.gateway.dto.ChatCompletionRequest;
import io.github.maixiaowen.gateway.dto.ChatCompletionResponse;
import io.github.maixiaowen.gateway.exception.UnknownProviderException;
import io.github.maixiaowen.gateway.provider.Provider;
import io.github.maixiaowen.gateway.provider.ProviderRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * <b>G3-3a 的验收物：注册表能正确解析渠道，并且在配置错误时"启动即失败"。</b>
 *
 * <p><b>★ 这个测试刻意不起 Spring ★</b>：
 * 注册表是个<b>纯逻辑类</b> —— 给它一个 Provider 列表和一个默认名，它就该给出确定性结果。
 * 用 {@code @SpringBootTest} 去测它，等于为了验一个纯函数把整个应用启动一遍：
 * <b>慢、脆、而且掩盖了"这个类其实不依赖框架"这个事实。</b>
 *
 * <p><b>判据</b>：被测类<b>能不能被 new 出来</b>。能，就用普通单测；
 * 只有真的需要容器装配（比如要验 {@code @Value} 注入、事务、Web 层）时才起 Spring。
 * <b>测试起 Spring 的成本，应该由"被测对象真的依赖容器"来买单。</b>
 *
 * <p><b>这里用桩 Provider 而不是真实的 OpenAiCompatibleProvider</b>：
 * 真实的那个需要 WebClient、ObjectMapper、五个配置项 —— 全都和"注册表解析"无关。
 * <b>测试替身应该只替换掉"无关的部分"，而不是把整个依赖树拖进来。</b>
 * （这也正是项目一里"纯 Mockito 单测能在空机器上跑绿"的同一个道理。）
 */
@DisplayName("G3-3a 渠道注册表")
class ProviderRegistryTest {

    /** 最小桩：只关心 name()，另外两个方法永远不会被本测试调到 */
    private static Provider stub(String name) {
        return new Provider() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Mono<ChatCompletionResponse> chatCompletion(ChatCompletionRequest request) {
                throw new UnsupportedOperationException("本测试不涉及补全逻辑");
            }

            @Override
            public Flux<ChatCompletionChunk> chatCompletionStream(ChatCompletionRequest request) {
                throw new UnsupportedOperationException("本测试不涉及补全逻辑");
            }
        };
    }

    @Test
    @DisplayName("默认渠道按配置解析，且能按名字取到具体渠道")
    void resolvesDefaultAndByName() {
        Provider deepseek = stub("deepseek");
        Provider qwen = stub("qwen");

        ProviderRegistry registry = new ProviderRegistry(List.of(deepseek, qwen), "deepseek");

        assertThat(registry.defaultProvider())
                .as("默认应解析成配置里写的那个")
                .isSameAs(deepseek);
        assertThat(registry.resolve("qwen"))
                .as("按名字应取到对应的那个实例")
                .isSameAs(qwen);
        assertThat(registry.names())
                .as("注册表应能报出全部渠道名")
                .containsExactlyInAnyOrder("deepseek", "qwen");
    }

    @Test
    @DisplayName("配置的默认渠道不存在 → 启动即失败，不能等请求进来了才 500")
    void failsFastWhenDefaultProviderIsMissing() {
        // 这是本类最重要的一条测试。
        // "启动失败"是可发现的事故 —— 部署流程会拦住、日志一眼看到；
        // "请求时才报错"是静默的灾难 —— 探针是绿的，但每个请求都 500。
        assertThatThrownBy(() -> new ProviderRegistry(List.of(stub("deepseek")), "qwen"))
                .as("配置写了 qwen 但没有任何实现叫这个名字，必须在构造时就炸")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("qwen")
                .hasMessageContaining("deepseek");   // 错误信息里要列出实际可用的，省得再去翻代码
    }

    @Test
    @DisplayName("两个实现用了同一个渠道名 → 启动即失败，不能静默覆盖")
    void failsFastOnDuplicateProviderName() {
        // 重名的后果很阴险：后注册的会静默覆盖前一个，
        // 表现是"我改的代码没生效" —— 代码改了、测试也绿了，就是跑的不是它。
        assertThatThrownBy(() -> new ProviderRegistry(List.of(stub("deepseek"), stub("deepseek")), "deepseek"))
                .as("重名必须炸，否则一个渠道会人间蒸发")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("重复")
                .hasMessageContaining("deepseek");
    }

    @Test
    @DisplayName("一个渠道都没有 → 启动即失败")
    void failsFastWhenNoProviderAtAll() {
        assertThatThrownBy(() -> new ProviderRegistry(List.of(), "deepseek"))
                .as("网关没有上游渠道就没法工作，不该装作启动成功")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("没有任何 Provider");
    }

    @Test
    @DisplayName("渠道标识为空白 → 启动即失败")
    void failsFastOnBlankProviderName() {
        assertThatThrownBy(() -> new ProviderRegistry(List.of(stub("   ")), "deepseek"))
                .as("空白名字会让配置和日志都失去意义")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("空值");
    }

    @Test
    @DisplayName("按名字取不存在的渠道 → 抛语义明确的异常，且带上可用列表")
    void unknownProviderThrowsWithAvailableNames() {
        ProviderRegistry registry = new ProviderRegistry(List.of(stub("deepseek"), stub("qwen")), "deepseek");

        assertThatThrownBy(() -> registry.resolve("kimi"))
                .as("不能返回 null —— 那会让调用方在很远的地方 NPE，看不出问题源头")
                .isInstanceOf(UnknownProviderException.class)
                .hasMessageContaining("kimi")
                .hasMessageContaining("deepseek")
                .hasMessageContaining("qwen");

        // 异常本身也要带上结构化字段：G3-3b 的异常处理器要靠它填错误响应，
        // 而不是去 parse message 字符串（那太脆了）
        UnknownProviderException ex = catchThrowableOfType(
                () -> registry.resolve("kimi"), UnknownProviderException.class);
        assertThat(ex.requestedName()).isEqualTo("kimi");
        assertThat(ex.availableNames()).isEqualTo(Set.of("deepseek", "qwen"));
    }

    // ========================================================================
    // 以下是 G3b 新增：路由。三条测试对应 resolveOrDefault 的三种输入。
    //
    // 这三条是成对的，缺一条就会让"路由"退化成"随便返回个渠道"：
    //   ① 指定了 → 必须是指定的那个（否则路由根本没生效）
    //   ② 没指定 → 必须是默认的（否则标准 OpenAI 客户端全部调不通）
    //   ③ 指定了不存在的 → 必须报错（否则静默走错渠道，而且没人知道）
    // ========================================================================

    @Test
    @DisplayName("G3b：请求指定了渠道 → 路由到指定的那个，不是默认的")
    void resolveOrDefaultHonorsExplicitChoice() {
        Provider deepseek = stub("deepseek");
        Provider moonshot = stub("moonshot");
        ProviderRegistry registry = new ProviderRegistry(List.of(deepseek, moonshot), "deepseek");

        assertThat(registry.resolveOrDefault("moonshot"))
                .as("指定了 moonshot 却拿到默认的 deepseek —— 那路由就是摆设")
                .isSameAs(moonshot);
    }

    @Test
    @DisplayName("G3b：请求没指定渠道（null 或空白）→ 回落默认，不能报错")
    void resolveOrDefaultFallsBackWhenNothingRequested() {
        Provider deepseek = stub("deepseek");
        Provider moonshot = stub("moonshot");
        ProviderRegistry registry = new ProviderRegistry(List.of(deepseek, moonshot), "deepseek");

        // 这条测试守的是"标准客户端能不能用"。
        // 真实的 OpenAI SDK / LangChain4j 压根不知道"渠道"这个概念，
        // 它们永远不发 provider 字段 —— 对它们来说 null 是<b>正常情况</b>，不是错误。
        // 如果这里报错，网关就把所有标准客户端都拒之门外了。
        assertThat(registry.resolveOrDefault(null))
                .as("没指定时必须走默认 —— 否则任何标准 OpenAI 客户端都调不通")
                .isSameAs(deepseek);
        assertThat(registry.resolveOrDefault("   "))
                .as("空白字符串要当'没指定'处理。客户端很容易传个空串进来，"
                        + "把它当成'指定了一个叫空格的渠道'然后报 400 是没道理的")
                .isSameAs(deepseek);
    }

    @Test
    @DisplayName("G3b：指定了不存在的渠道 → 报错，绝不静默回落默认")
    void resolveOrDefaultRejectsUnknownInsteadOfSilentlyFallingBack() {
        ProviderRegistry registry = new ProviderRegistry(List.of(stub("deepseek"), stub("moonshot")), "deepseek");

        // ★ 这条是三条里最重要的，因为它守的是一个"看起来更友好"的错误做法。
        //
        // 很多网关在"指定了不存在的渠道"时选择回落默认，理由是"保证可用性"。
        // 后果：调用方把渠道名拼错<b>三个月都没人发现</b> ——
        // 请求全都成功了（走了默认渠道），日志里一条错误都没有，
        // 只是账单一直出在错误的渠道上、或者该走便宜渠道的批量任务一直在烧贵的。
        //
        // 可用性不能靠"假装请求是对的"来换。
        assertThatThrownBy(() -> registry.resolveOrDefault("kimi"))
                .as("指定了不存在的渠道必须炸 —— 静默回落是'看起来更友好'的错答案")
                .isInstanceOf(UnknownProviderException.class)
                .hasMessageContaining("kimi");
    }
}
