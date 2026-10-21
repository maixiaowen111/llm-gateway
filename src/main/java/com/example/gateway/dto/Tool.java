package com.example.gateway.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * <b>G9：一个工具定义（OpenAI Function Calling 的 {@code tools[]} 元素）。</b>
 *
 * <pre>
 * {"type":"function",
 *  "function":{"name":"queryOrder",
 *              "description":"按订单号查询订单状态",
 *              "parameters":{"type":"object","properties":{...},"required":[...]}}}
 * </pre>
 *
 * <p><b>★ 为什么 {@code parameters} 是 {@link JsonNode}，而其余字段是有类型的 ★</b>
 * <p>{@code name} / {@code description} 的结构永远不会变，它们是稳定的；
 * 但 {@code parameters} 是一份<b>由业务方自由书写的 JSON Schema</b> ——
 * 网关没有理由、也没有能力去理解每个工具的参数结构。
 * <b>对网关来说，它是不透明载荷；对模型来说，它是调用依据。</b>
 * 网关的正确做法是"原样搬运"，而不是"解析后重建" ——
 * 重建会丢掉它不认识的 JSON Schema 关键字（比如 {@code oneOf} / {@code pattern}），
 * 而那几个关键字恰好可能是模型选对参数的唯一依据。
 *
 * <p><b>⚠️ 一个必须接受的现实</b>：不同渠道对 tools 的支持程度不一样。
 * OpenAI 兼容渠道原样透传即可；协议不同的渠道（如 Anthropic）需要一整套
 * tools ↔ input_schema 的翻译。<b>不能假装它们一样，也不能悄悄把 tools 丢掉</b>
 * —— 丢掉之后请求仍然 200，只是模型不再调用任何工具，这种故障在日志里毫无痕迹。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Tool(String type, Function function) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Function(String name, String description, JsonNode parameters) {
    }
}
