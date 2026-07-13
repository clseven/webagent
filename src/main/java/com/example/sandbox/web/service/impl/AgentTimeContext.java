package com.example.sandbox.web.service.impl;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Locale;

/**
 * 单轮智能体使用的不可变时间快照。
 *
 * <p>该对象同时为规划器、普通执行器、社交轮和子智能体提供同一时刻，
 * 避免一次任务内部因多次读取系统时钟而出现相互矛盾的日期或时间。</p>
 *
 * @param currentDateTime 带业务时区的当前日期时间
 */
public record AgentTimeContext(ZonedDateTime currentDateTime) {

    /**
     * 将时间快照转换为模型可直接读取的动态提示词段。
     *
     * @return 包含本地日期时间、时区、星期和 UTC 时间的提示词
     */
    public String toPromptSection() {
        String localTime = currentDateTime.toOffsetDateTime()
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        String utcTime = currentDateTime.toInstant().toString();
        String weekday = currentDateTime.getDayOfWeek()
                .getDisplayName(TextStyle.FULL, Locale.SIMPLIFIED_CHINESE);

        return """
                ## 运行时上下文

                - 当前日期：%s
                - 当前时间：%s
                - 时区：%s
                - 星期：%s
                - UTC 时间：%s

                “今天、明天、最近、本周”等相对时间按上述时区解释。
                最新事实仍需通过搜索或相应工具验证。
                """.formatted(
                currentDateTime.toLocalDate(),
                localTime,
                currentDateTime.getZone().getId(),
                weekday,
                utcTime
        ).trim();
    }
}
