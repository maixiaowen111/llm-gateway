package io.github.maixiaowen.gateway.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * <b>API Key 校验器：把"客户端送来的 key"换成"已认证的 clientId"。</b>
 *
 * <p><b>★ 它只做一件事，而且这件事必须只有一份实现 ★</b>
 * <p>限流、计费、日志都依赖同一个身份。如果每个组件各自解析一次 key，
 * 迟早会出现"A 认为你是 alice、B 认为你是 bob"的情况 ——
 * 那不是 bug，是<b>账目对不上</b>。所以身份的解析只有这一个入口。
 *
 * <p><b>★ 为什么查表用"哈希 → clientId"的 Map ★</b>
 * <p>校验时对送来的 key 做 SHA-256，拿摘要去 Map 里查。好处：
 * <ol>
 *   <li>不需要遍历所有 key 做比较，key 数量增长时仍然是 O(1)；</li>
 *   <li>内存里只有摘要，<b>即使进程被 dump，也拿不到可用的 key</b>；</li>
 *   <li>比较的是摘要而不是明文，不存在"前几位对了就提前返回"的短路。</li>
 * </ol>
 * <p><b>⚠️ 不要在这里用 {@link MessageDigest#isEqual} 包一层"常量时间比较"然后遍历：
 * 那看起来更安全，实际上只是把 O(1) 变成 O(n)，而且 Map 的命中判断本身
 * 并不向攻击者泄露"正确的摘要"—— 摘要本来就是攻击者无法控制的值。</b>
 * 常量时间比较用在该用的地方（比如 {@code AdminController} 比较管理员 token），
 * 不是无脑到处抄。
 */
public final class ApiKeyStore {

    /**
     * key 的最小长度。
     *
     * <p>一个 16 字符的随机串仍有约 95 bit 熵，足够抵抗在线爆破；
     * 但真实网关的 key 通常是 32 字符以上。<b>这里拒绝短 key，
     * 是为了让"把 {@code test} 当 key 用"在启动期就被标出来</b>，
     * 而不是等到某天它被撞库成功。
     */
    private static final int MIN_KEY_LENGTH = 16;

    /** clientId 会进 Redis key 和日志，字符集必须收紧 —— 否则一个换行就能伪造日志行 */
    private static final Pattern CLIENT_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    /** SHA-256 的十六进制表示，固定 64 位 */
    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

    private final Map<String, String> clientIdByHash;
    private final int clientCount;
    private final int keyCount;

    public ApiKeyStore(AuthProperties properties) {
        Map<String, String> parsed = new LinkedHashMap<>();
        Set<String> clientIds = new LinkedHashSet<>();

        for (String entry : properties.keys()) {
            if (entry == null || entry.isBlank()) {
                throw new IllegalStateException(
                        "gateway.auth.keys 中存在空项。格式必须是 clientId:sha256Hex。");
            }

            // 只按第一个冒号切：clientId 不允许冒号（下面会校验），
            // 所以切出来的左半边一定是 ID，右半边是摘要。
            int separator = entry.indexOf(':');
            if (separator <= 0 || separator == entry.length() - 1) {
                throw new IllegalStateException(
                        "gateway.auth.keys 中的项格式错误：「" + entry + "」。"
                                + "要求 clientId:sha256Hex，例如 demo:1fcd8ad3...");
            }

            String clientId = entry.substring(0, separator).trim();
            String hash = entry.substring(separator + 1).trim().toLowerCase(Locale.ROOT);

            if (!CLIENT_ID.matcher(clientId).matches()) {
                throw new IllegalStateException(
                        "clientId「" + clientId + "」不合法：只允许字母、数字、点、下划线、短横线，"
                                + "长度 1~64。★ 它会进 Redis key 和日志，不能放开字符集 ★");
            }
            if (!SHA256_HEX.matcher(hash).matches()) {
                throw new IllegalStateException(
                        "clientId=" + clientId + " 的 sha256 不是 64 位小写十六进制。"
                                + "生成方式：printf '%s' '你的key' | sha256sum");
            }

            String previous = parsed.putIfAbsent(hash, clientId);
            if (previous != null && !previous.equals(clientId)) {
                // 同一把 key 属于两个客户 —— 这是配置事故：谁付钱？必须拒绝启动。
                throw new IllegalStateException(
                        "同一把 API Key 的摘要同时映射到了两个 clientId："
                                + previous + " 和 " + clientId + "。这会导致计费主体不确定。");
            }
            clientIds.add(clientId);
        }

        this.clientIdByHash = Map.copyOf(parsed);
        // 留一个只读视图给启动日志用（不要打印任何摘要或明文 key）
        this.clientCount = clientIds.size();
        this.keyCount = parsed.size();
    }

    /**
     * 校验 key。
     *
     * @return 已认证的 clientId；key 为空/过短/不匹配时返回 {@link Optional#empty()}
     */
    public Optional<String> authenticate(String presentedKey) {
        if (presentedKey == null) {
            return Optional.empty();
        }
        String key = presentedKey.trim();
        // ★ 过短的 key 直接拒，不做哈希：省一次无意义的计算，
        //   也避免"空字符串/空格"这类输入进入哈希路径。
        if (key.length() < MIN_KEY_LENGTH) {
            return Optional.empty();
        }
        return Optional.ofNullable(clientIdByHash.get(sha256Hex(key)));
    }

    public int clientCount() {
        return clientCount;
    }

    public int keyCount() {
        return keyCount;
    }

    /**
     * SHA-256 十六进制（小写）。
     *
     * <p><b>为什么手写而不是用 Guava / Commons Codec</b>：三个 JDK 自带的类就够了。
     * 网关是<b>流量入口</b>，依赖越少，供应链面越小 ——
     * 一个只为了一行哈希引入的工具库，不值得。
     */
    static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // 每个 Java 平台都必须提供 SHA-256；走到这里说明 JDK 坏了。
            // 不能降级成"跳过校验"—— 那会把一个环境问题变成一次安全漏洞。
            throw new IllegalStateException("当前 JDK 不提供 SHA-256，无法校验 API Key", e);
        }
    }
}
