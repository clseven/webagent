package com.example.sandbox.web.service.impl;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * 智能体时间上下文服务，负责按配置时区创建单轮不可变快照。
 *
 * <p>生产环境使用系统时钟；测试可注入固定 {@link Clock}，确保日期相关行为可复现。</p>
 */
@Service
public class AgentTimeContextService {

    /** 未显式配置时使用的业务时区。 */
    public static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");

    /** 创建时间快照所使用的可注入时钟。 */
    private final Clock clock;

    /**
     * 使用应用配置构造生产时间服务。
     *
     * @param zoneId IANA 时区标识；配置非法时应用启动失败，避免静默使用错误时间
     */
    @Autowired
    public AgentTimeContextService(@Value("${agent.time-zone:Asia/Shanghai}") String zoneId) {
        this(Clock.system(ZoneId.of(zoneId)));
    }

    /**
     * 使用指定时钟构造时间服务，主要供确定性测试使用。
     *
     * @param clock 不可为空的时间源，时钟自身携带默认业务时区
     */
    AgentTimeContextService(Clock clock) {
        this.clock = clock;
    }

    /**
     * 创建使用默认业务时区的兼容服务实例。
     *
     * @return 使用上海时区系统时钟的时间服务
     */
    static AgentTimeContextService systemDefault() {
        return new AgentTimeContextService(Clock.system(DEFAULT_ZONE));
    }

    /**
     * 读取当前时刻并生成默认业务时区的快照。
     *
     * @return 本次调用对应的不可变时间快照
     */
    public AgentTimeContext snapshot() {
        return new AgentTimeContext(ZonedDateTime.now(clock));
    }

    /**
     * 读取当前时刻并转换为请求的 IANA 时区。
     *
     * @param zoneId IANA 时区标识，例如 {@code UTC} 或 {@code America/New_York}
     * @return 指定时区下的不可变时间快照
     * @throws java.time.DateTimeException 时区标识非法时抛出
     */
    public AgentTimeContext snapshot(String zoneId) {
        ZoneId requestedZone = ZoneId.of(zoneId);
        return new AgentTimeContext(ZonedDateTime.ofInstant(clock.instant(), requestedZone));
    }
}
