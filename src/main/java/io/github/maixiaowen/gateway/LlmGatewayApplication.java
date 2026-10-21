package io.github.maixiaowen.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * LLM 模型网关启动类。
 *
 * <p>Gateway 在架构里的位置：所有上游大模型调用的唯一入口。
 * 业务侧不直连 DeepSeek / OpenAI，一律打到这里，由网关负责
 * 渠道路由、限流、熔断、Token 计量。
 */
@SpringBootApplication
public class LlmGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(LlmGatewayApplication.class, args);
    }
}
