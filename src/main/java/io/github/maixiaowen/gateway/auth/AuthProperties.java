package io.github.maixiaowen.gateway.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * <b>客户端鉴权的全部配置（G10）。</b>
 *
 * <p><b>★ 它解决的是整个网关最要命的一个洞 ★</b>
 * <p>在 G10 之前，客户端身份来自 {@code X-Client-Id} —— 一个<b>客户端自己填的字符串</b>。
 * 限流和计费都按它归属，于是任何人都可以：
 * <ul>
 *   <li>填别人的 ID，<b>花别人的余额</b>；</li>
 *   <li>每次请求换一个随机 ID，<b>让限流器永远看到一个"新客户"</b>；</li>
 *   <li>伪造一个内部客户名，在账单上留下一条查无此人的记录。</li>
 * </ul>
 * 这不是"权限设计不完善"，这是<b>把计费系统建在一个客户端可以随意改写的字段上</b>。
 * 身份必须先被证明，然后才能用来记账 —— <b>先有身份，才有限流和计费。</b>
 *
 * <p><b>★ 为什么密钥只存 SHA-256，不存明文 ★</b>
 * <p>配置文件会进 Git、会被打进镜像、会被复制到每个人的电脑上。
 * 明文密钥一旦落进这些地方，就等于泄露 —— 而且<b>泄露之后你无法从日志上发现它</b>。
 * 网关只保存摘要，校验时对客户端送来的 key 做同样的摘要再比对：
 * <pre>
 *   SHA-256("sk-gw-dev-demo-key-0123456789")
 *     = 1fcd8ad3c239fa16ed49dd131f0e038f5447b8b8fefa75de15b15fb47de45ade
 * </pre>
 * <b>API Key 是高熵随机串，不需要像用户密码那样加盐/慢哈希</b>
 * （bcrypt 的慢是为了对抗"低熵密码被字典爆破"，而 128 位随机串没有字典可查）。
 * 这一点要在面试里说清楚，否则会被追问"为什么不用 bcrypt"。
 *
 * <p><b>⚠️ 这仍然是一个"配置态"的密钥表，不是完整的租户体系</b>
 * <p>生产上密钥应该来自数据库 / KMS，支持创建、吊销、轮换、审计。
 * 这里做成配置，是因为<b>本项目还没有接入方管理系统</b>，
 * 而"先把身份校验这件事做对"比"假装做了一个不存在的租户后台"更有价值。
 * <b>已知边界要写出来，而不是用一个看起来很大的词把它盖住。</b>
 *
 * @param enabled 是否启用。默认 false：本地开发可以直接调，但启动会打 WARN
 * @param keys    密钥表。每一项的格式是 {@code clientId:sha256Hex}。
 *                同一个 clientId 可以出现多次 —— 那是<b>密钥轮换</b>：
 *                新旧两把 key 同时有效，客户端换完之后再把旧的那行删掉。
 *                <b>轮换必须能不中断服务地完成，否则它永远不会被执行。</b>
 */
@ConfigurationProperties(prefix = "gateway.auth", ignoreUnknownFields = false)
public record AuthProperties(
        boolean enabled,
        List<String> keys
) {

    public AuthProperties {
        keys = keys == null ? List.of() : List.copyOf(keys);

        if (enabled && keys.isEmpty()) {
            // ★ fail-closed：打开了鉴权却没有配任何 key，后果不是"谁都能进"吗？
            //   如果这里放过，filter 会把所有 /v1 请求都拒掉 —— 服务等于不可用。
            //   但更糟的是另一种实现：把"没有 key"理解成"不校验"。
            //   所以必须在启动期炸掉，让人一眼看到配置漏了。
            throw new IllegalStateException(
                    "gateway.auth.enabled=true，但 gateway.auth.keys 为空。"
                            + "打开鉴权却没有配置任何密钥，网关将拒绝所有业务请求。"
                            + "请配置 clientId:sha256Hex（格式示例见 application.yml），"
                            + "或把 GATEWAY_AUTH_ENABLED 设为 false。");
        }
    }
}
