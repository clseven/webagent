package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.model.entity.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证执行器 system prompt 按实际工具能力分段组装。
 */
class ReactPromptAssemblerTest {

    /**
     * 验证普通工具场景不会加载技能、子代理、浏览器和 MCP 指令。
     */
    @Test
    void omitsOptionalSectionsWhenToolsAreAbsent() {
        String prompt = ReactPromptAssembler.assemble(
                List.of(tool("read_file")),
                "",
                """
                        ### 目标状态
                        读取文件
                        """);

        assertThat(prompt)
                .contains("你是沙箱环境中的执行者")
                .contains("## 工作空间")
                .contains("## 可用工具")
                .contains("─── 以下为动态段");
        assertThat(prompt)
                .doesNotContain("## 技能系统")
                .doesNotContain("## 浏览器工具")
                .doesNotContain("## 子代理")
                .doesNotContain("## MCP 动态工具管理");
        assertThat(prompt.indexOf("## 可用工具")).isLessThan(prompt.indexOf("─── 以下为动态段"));
        assertThat(prompt.indexOf("─── 以下为动态段")).isLessThan(prompt.indexOf("### 目标状态"));
    }

    /**
     * 验证可选 section 基于真实工具定义加载，而不是基于用户话术猜测。
     */
    @Test
    void loadsOptionalSectionsFromActualToolDefinitions() {
        String prompt = ReactPromptAssembler.assemble(
                List.of(
                        tool("browser_screenshot"),
                        tool("browser_execute"),
                        tool("skill_list"),
                        tool("run_subagent"),
                        tool("mcp_add_or_update_server")
                ),
                "## 已启用技能\n- brainstorming: 讨论设计",
                "");

        assertThat(prompt)
                .contains("## 浏览器工具")
                .contains("使用 browser_screenshot 将画面呈现给用户")
                .contains("ref 只是快照中的阅读编号")
                .contains("## 技能系统")
                .contains("## 子代理")
                .contains("## MCP 动态工具管理")
                .contains("## 已启用技能");
        assertThat(prompt.indexOf("## 可用工具")).isLessThan(prompt.indexOf("─── 以下为动态段"));
        assertThat(prompt.indexOf("─── 以下为动态段")).isLessThan(prompt.indexOf("## 已启用技能"));
    }

    /**
     * 验证当前提示词不会引导普通工具并行调用，直到执行循环支持多 tool call。
     */
    @Test
    void keepsOrdinaryToolUseSerialUntilMultipleToolCallsAreSupported() {
        String prompt = ReactPromptAssembler.assemble(
                List.of(tool("read_file"), tool("run_subagent")),
                "",
                "");

        assertThat(prompt)
                .contains("每轮只处理一个普通工具调用")
                .contains("需要并行推进多个独立任务时，优先使用 run_subagent")
                .doesNotContain("互不依赖时可并行");
    }

    /**
     * 验证 todo_write 可用时加载运行时任务清单约束。
     */
    @Test
    void loadsTodoWriteSectionWhenToolAvailable() {
        String prompt = ReactPromptAssembler.assemble(
                List.of(tool("todo_write"), tool("read_file")),
                "",
                "");

        assertThat(prompt)
                .contains("## TodoWrite 运行时任务清单")
                .contains("多步任务开始前使用 todo_write 建立任务清单")
                .contains("TodoState 是看板和账本，不调度工具")
                .contains("最终回答前确认关键 todo 已 completed 或 blocked");
        assertThat(ReactPromptAssembler.sectionNames(List.of(tool("todo_write")), "", ""))
                .contains("todo_write");
    }

    /**
     * 构造测试用工具定义。
     *
     * @param name 工具名称
     * @return 简化工具定义
     */
    private ToolDefinition tool(String name) {
        return new ToolDefinition(name, "测试描述", Map.of("type", "object"), "AIO");
    }
}
