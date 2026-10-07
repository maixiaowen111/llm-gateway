package com.example.gateway.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 网关对外的<b>错误</b>契约（OpenAI 兼容）。
 *
 * <p><b>为什么错误也要用 OpenAI 的格式？</b>
 * 1c 定了"对外就是 OpenAI 协议"。那出错时也必须一致 ——
 * 客户端的 OpenAI SDK 是按 {@code {"error":{"message","type","code"}}} 解析错误的，
 * 你返回 Spring 默认的 {@code {"timestamp","status","path"}}，SDK 直接解析失败，
 * 用户看到的是"未知错误"而不是你的提示。
 *
 * <p>一句判据：<b>成功和失败的契约，要么都是我们的，要么都是标准的，不能一半一半。</b>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OpenAiErrorResponse(ErrorBody error) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorBody(
            String message,
            /** 错误大类，OpenAI 用的是 invalid_request_error / server_error 这类 */
            String type,
            /** 机器可读的错误码，G5 的渠道转移就靠它判断"这次失败该不该换渠道重试" */
            String code,
            String requestId
    ) {
    }
}
