package com.example.sandbox.web.service.tool;

import com.example.sandbox.web.model.entity.ToolDefinition;
import com.example.sandbox.web.service.Tool;
import com.example.sandbox.web.service.ToolSideEffect;
import com.example.sandbox.web.service.impl.AgentTimeContext;
import com.example.sandbox.web.service.impl.AgentTimeContextService;
import org.springframework.stereotype.Component;

import java.time.DateTimeException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 当前时间工具，为需要新鲜时刻或跨时区换算的任务提供权威时间。
 *
 * <p>普通相对时间问题优先使用单轮提示词快照；只有需要执行时刻或指定时区时才调用本工具。</p>
 */
@Component
public class CurrentTimeTool implements Tool {

    /** 工具注册名称。 */
    private static final String NAME = "current_time";

    /** 创建最新时间快照的服务。 */
    private final AgentTimeContextService timeContextService;

    /**
     * 构造当前时间工具。
     *
     * @param timeContextService 时间快照服务
     */
    public CurrentTimeTool(AgentTimeContextService timeContextService) {
        this.timeContextService = timeContextService;
    }

    /**
     * 返回工具名称、用途和可选时区参数定义。
     *
     * @return 当前时间工具定义
     */
    @Override
    public ToolDefinition getDefinition() {
        Map<String, Object> timeZone = new LinkedHashMap<>();
        timeZone.put("type", "string");
        timeZone.put("description", "可选的 IANA 时区，例如 UTC、Asia/Shanghai、America/New_York");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("time_zone", timeZone);

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", properties);
        parameters.put("required", List.of());

        return new ToolDefinition(
                NAME,
                "获取执行时刻的当前日期和时间；可按指定 IANA 时区返回。涉及最新事实时仍需使用搜索或相应工具验证。",
                parameters,
                "ALL"
        );
    }

    /**
     * 获取调用时刻的新快照，并按可选时区格式化返回。
     *
     * @param sessionId 会话标识；本工具不读取会话状态
     * @param arguments 可包含 {@code time_zone} 的工具参数
     * @return 格式化时间，或非法时区对应的中文错误信息
     */
    @Override
    public String execute(String sessionId, Map<String, Object> arguments) {
        Object rawTimeZone = arguments == null ? null : arguments.get("time_zone");
        String requestedZone = rawTimeZone == null ? "" : String.valueOf(rawTimeZone).trim();

        try {
            AgentTimeContext snapshot = requestedZone.isBlank()
                    ? timeContextService.snapshot()
                    : timeContextService.snapshot(requestedZone);
            return snapshot.toPromptSection();
        } catch (DateTimeException e) {
            return "错误：无效的时区 - " + requestedZone;
        }
    }

    /**
     * 当前时间查询不修改共享状态，可与其他只读工具并发。
     *
     * @return 只读副作用类型
     */
    @Override
    public ToolSideEffect getSideEffect() {
        return ToolSideEffect.READ;
    }
}
