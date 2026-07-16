package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.model.llm.LlmToolCall;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证 DeepSeek V4 DSML 工具调用的流式隔离和结构化恢复。
 */
class DeepSeekDsmlStreamParserTest {

    /** JSON 解析器，模拟生产 LLM 适配层的参数类型恢复。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 验证官方 DSML 格式会保留可读过渡文字，并恢复字符串和非字符串参数。
     */
    @Test
    void parsesCanonicalDsmlAndPreservesReadableText() {
        String response = "我先查询两个方向。"
                + "<｜DSML｜tool_calls>\n"
                + "<｜DSML｜invoke name=\"web_search\">\n"
                + "<｜DSML｜parameter name=\"query\" string=\"true\">墨刀 MCP 发布时间</｜DSML｜parameter>\n"
                + "<｜DSML｜parameter name=\"limit\" string=\"false\">5</｜DSML｜parameter>\n"
                + "</｜DSML｜invoke>\n"
                + "</｜DSML｜tool_calls>";

        DeepSeekDsmlStreamParser.ParseResult result =
                DeepSeekDsmlStreamParser.parseComplete(objectMapper, response);

        assertThat(result.visibleText()).isEqualTo("我先查询两个方向。");
        assertThat(result.protocolDetected()).isTrue();
        assertThat(result.toolCalls()).hasSize(1);
        assertThat(result.toolCalls().get(0).name()).isEqualTo("web_search");
        assertThat(result.toolCalls().get(0).arguments())
                .containsEntry("query", "墨刀 MCP 发布时间")
                .containsEntry("limit", 5);
    }

    /**
     * 验证线上观察到的全角双竖线格式即使逐字符分块，也不会泄漏协议文字。
     */
    @Test
    void parsesObservedDoublePipeVariantAcrossSingleCharacterChunks() {
        String response = "用户想知道发布时间，我先搜索。"
                + "<｜｜DSML｜｜tool_calls>"
                + "<｜｜DSML｜｜invoke name=\"web_search\">"
                + "<｜｜DSML｜｜parameter name=\"query\" string=\"true\">site:modao.cc MCP 发布时间</｜｜DSML｜｜parameter>"
                + "</｜｜DSML｜｜invoke>"
                + "<｜｜DSML｜｜invoke name=\"web_search\">"
                + "<｜｜DSML｜｜parameter name=\"query\" string=\"true\">墨刀 MCP 上线时间</｜｜DSML｜｜parameter>"
                + "</｜｜DSML｜｜invoke>"
                + "</｜｜DSML｜｜tool_calls>";
        DeepSeekDsmlStreamParser parser = new DeepSeekDsmlStreamParser(objectMapper);
        List<String> visible = new ArrayList<>();

        response.codePoints()
                .mapToObj(Character::toString)
                .forEach(fragment -> visible.addAll(parser.accept(fragment)));
        DeepSeekDsmlStreamParser.StreamCompletion completion = parser.complete();
        visible.addAll(completion.visibleFragments());

        String visibleText = String.join("", visible);
        assertThat(visibleText).isEqualTo("用户想知道发布时间，我先搜索。");
        assertThat(visibleText).doesNotContain("DSML", "tool_calls", "invoke");
        assertThat(completion.toolCalls())
                .extracting(LlmToolCall::name)
                .containsExactly("web_search", "web_search");
        assertThat(completion.toolCalls().get(0).arguments())
                .containsEntry("query", "site:modao.cc MCP 发布时间");
    }

    /**
     * 验证数组、对象、布尔值和 null 会按 {@code string="false"} 恢复原始 JSON 类型。
     */
    @Test
    void restoresNonStringJsonTypes() {
        String response = "<||DSML||tool_calls>"
                + "<||DSML||invoke name=\"complex_tool\">"
                + "<||DSML||parameter name=\"items\" string=\"false\">[1,2]</||DSML||parameter>"
                + "<||DSML||parameter name=\"config\" string=\"false\">{\"enabled\":true}</||DSML||parameter>"
                + "<||DSML||parameter name=\"optional\" string=\"false\">null</||DSML||parameter>"
                + "</||DSML||invoke>"
                + "</||DSML||tool_calls>";

        DeepSeekDsmlStreamParser.ParseResult result =
                DeepSeekDsmlStreamParser.parseComplete(objectMapper, response);

        Map<String, Object> arguments = result.toolCalls().get(0).arguments();
        assertThat(arguments.get("items")).isEqualTo(List.of(1, 2));
        assertThat(arguments.get("config")).isEqualTo(Map.of("enabled", true));
        assertThat(arguments).containsKey("optional");
        assertThat(arguments.get("optional")).isNull();
    }

    /**
     * 验证普通小于号和未完成的相似前缀不会被误吞，流结束时会原样释放。
     */
    @Test
    void flushesPlainTextThatOnlyLooksLikeMarkerPrefix() {
        DeepSeekDsmlStreamParser parser = new DeepSeekDsmlStreamParser(objectMapper);
        List<String> visible = new ArrayList<>();
        visible.addAll(parser.accept("比较结果是 2 < 3，末尾文字 <｜D"));
        DeepSeekDsmlStreamParser.StreamCompletion completion = parser.complete();
        visible.addAll(completion.visibleFragments());

        assertThat(String.join("", visible)).isEqualTo("比较结果是 2 < 3，末尾文字 <｜D");
        assertThat(completion.protocolDetected()).isFalse();
        assertThat(completion.toolCalls()).isEmpty();
    }

    /**
     * 验证已经进入 DSML 协议但缺少结束标签时会安全失败，不会释放原始协议。
     */
    @Test
    void rejectsIncompleteProtocolWithoutReleasingRawMarkup() {
        DeepSeekDsmlStreamParser parser = new DeepSeekDsmlStreamParser(objectMapper);
        List<String> visible = parser.accept(
                "准备搜索。<｜DSML｜tool_calls><｜DSML｜invoke name=\"web_search\">");

        assertThat(String.join("", visible)).isEqualTo("准备搜索。");
        assertThatThrownBy(parser::complete)
                .isInstanceOf(DeepSeekDsmlStreamParser.ProtocolException.class)
                .hasMessageContaining("不完整");
    }

    /**
     * 验证非法非字符串参数不会退化为空 Map 或普通聊天文字。
     */
    @Test
    void rejectsInvalidNonStringParameterJson() {
        String response = "<｜DSML｜tool_calls>"
                + "<｜DSML｜invoke name=\"web_search\">"
                + "<｜DSML｜parameter name=\"limit\" string=\"false\">not-json</｜DSML｜parameter>"
                + "</｜DSML｜invoke>"
                + "</｜DSML｜tool_calls>";

        assertThatThrownBy(() -> DeepSeekDsmlStreamParser.parseComplete(objectMapper, response))
                .isInstanceOf(DeepSeekDsmlStreamParser.ProtocolException.class)
                .hasMessageContaining("不是合法 JSON");
    }

    /**
     * 验证空的 reasoning 字段保持 null，避免协议兼容逻辑改变既有响应语义。
     */
    @Test
    void preservesNullInput() {
        DeepSeekDsmlStreamParser.ParseResult result =
                DeepSeekDsmlStreamParser.parseComplete(objectMapper, null);

        assertThat(result.visibleText()).isNull();
        assertThat(result.toolCalls()).isEmpty();
        assertThat(result.protocolDetected()).isFalse();
    }
}
