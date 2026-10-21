package io.github.maixiaowen.gateway.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * <b>G9：一次工具调用（模型"请求你执行某个工具"的凭证）。</b>
 *
 * <pre>
 * {"id":"call_abc",
 *  "type":"function",
 *  "function":{"name":"queryOrder","arguments":"{\"orderNo\":\"A1001\"}"}}
 * </pre>
 *
 * <p><b>★ 注意 {@code arguments} 是【字符串】，不是对象 ★</b>
 * <p>这是 OpenAI 协议里最容易被误解的一处：它是一段<b>序列化后的 JSON 文本</b>。
 * 模型是"逐字生成"的，它把参数一个字符一个字符写出来 —— 写成字符串，
 * 协议就不需要保证这半截 JSON 在流式传输的每个片段里都是合法的。
 * <b>所以网关也必须原样透传字符串，不能先 parse 成对象再序列化回去</b>：
 * 那一步会重新格式化 JSON（空格、键顺序、浮点表示），
 * 而严格的客户端可能在做签名或哈希校验。
 *
 * <p><b>它为什么同时出现在请求和响应里</b>：
 * <ul>
 *   <li>响应方向：模型说"我要调用 queryOrder"；</li>
 *   <li>请求方向：第二轮把这句话原样带回去（assistant 消息的 {@code tool_calls}），
 *       后面再跟一条 {@code role=tool} 的消息回填结果。
 *       <b>两轮之间这条凭证不能丢，也不能改 id</b> —— 丢了模型不知道结果属于哪次调用。</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ToolCall(String id, String type, Function function) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Function(String name, String arguments) {
    }
}
