package io.github.maixiaowen.gateway;

import io.github.maixiaowen.gateway.auth.ApiKeyStore;
import io.github.maixiaowen.gateway.auth.AuthProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <b>G10 的单元验收：key → clientId 的映射与配置校验。</b>
 *
 * <p>这一层不需要 Spring、不需要 Docker —— 它验的是<b>纯粹的判定逻辑</b>。
 * 端到端测试（{@code ClientAuthEndToEndTest}）验"401 真的出现在 HTTP 上"，
 * 本类验"哪些输入应该被拒、哪些配置错误必须启动失败"。
 */
@DisplayName("G10 客户端鉴权：密钥校验")
class ApiKeyStoreTest {

    private static final String ALICE_KEY = "sk-gw-alice-0123456789abcdef";
    private static final String ALICE_KEY_2 = "sk-gw-alice-rotated-9876543210";
    private static final String BOB_KEY = "sk-gw-bob-0123456789abcdef";

    @Test
    @DisplayName("有效 key 能换回 clientId")
    void validKeyResolvesClientId() {
        ApiKeyStore store = new ApiKeyStore(new AuthProperties(true,
                List.of("alice:" + sha256(ALICE_KEY), "bob:" + sha256(BOB_KEY))));

        assertThat(store.authenticate(ALICE_KEY)).contains("alice");
        assertThat(store.authenticate(BOB_KEY)).contains("bob");
        assertThat(store.clientCount()).isEqualTo(2);
        assertThat(store.keyCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("同一客户可以有多把 key —— 这是密钥轮换，不是配置错误")
    void multipleKeysPerClientSupportRotation() {
        ApiKeyStore store = new ApiKeyStore(new AuthProperties(true,
                List.of("alice:" + sha256(ALICE_KEY), "alice:" + sha256(ALICE_KEY_2))));

        assertThat(store.authenticate(ALICE_KEY)).contains("alice");
        assertThat(store.authenticate(ALICE_KEY_2)).contains("alice");
        assertThat(store.clientCount()).isEqualTo(1);
        assertThat(store.keyCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("错误 / 过短 / 空 key 一律拒绝")
    void invalidKeysAreRejected() {
        ApiKeyStore store = new ApiKeyStore(new AuthProperties(true,
                List.of("alice:" + sha256(ALICE_KEY))));

        assertThat(store.authenticate(null)).isEmpty();
        assertThat(store.authenticate("")).isEmpty();
        assertThat(store.authenticate("   ")).isEmpty();
        // 故意给一个和真 key 很像、但少一位的串 —— 摘要不同，必须拒绝
        assertThat(store.authenticate(ALICE_KEY.substring(0, ALICE_KEY.length() - 1))).isEmpty();
        assertThat(store.authenticate("sk-gw-someone-else-0123456789")).isEmpty();
    }

    @Test
    @DisplayName("同一把 key 映射到两个客户 → 拒绝启动（计费主体不能不确定）")
    void duplicateKeyForDifferentClientsFailsFast() {
        String hash = sha256(ALICE_KEY);
        assertThatThrownBy(() -> new ApiKeyStore(new AuthProperties(true,
                List.of("alice:" + hash, "bob:" + hash))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("同一把 API Key");
    }

    @Test
    @DisplayName("配置格式错误 → 启动失败，而不是静默跳过")
    void malformedEntriesFailFast() {
        assertThatThrownBy(() -> new ApiKeyStore(new AuthProperties(true, List.of("no-separator"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("格式错误");

        assertThatThrownBy(() -> new ApiKeyStore(new AuthProperties(true, List.of("alice:not-a-hash"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("64 位");

        assertThatThrownBy(() -> new ApiKeyStore(new AuthProperties(true, List.of("bad id:" + sha256(ALICE_KEY)))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("clientId");
    }

    @Test
    @DisplayName("打开鉴权却没有任何 key → 启动失败（不能理解成“不校验”）")
    void enabledWithoutKeysFailsFast() {
        assertThatThrownBy(() -> new AuthProperties(true, List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("keys 为空");
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
