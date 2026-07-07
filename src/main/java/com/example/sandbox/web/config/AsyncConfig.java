package com.example.sandbox.web.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步任务线程池配置
 *
 * @author example
 * @date 2026/07/07
 */
@Configuration
public class AsyncConfig {

    /**
     * 知识库文档处理线程池。
     *
     * <p>替代 Spring 默认的 {@code SimpleAsyncTaskExecutor}（每次新建线程、无上限、无队列），
     * 限制并发与队列长度，避免大批量上传或解析大体积 PDF 时线程数失控打爆 JVM。</p>
     *
     * <p>队列满时由 {@link ThreadPoolExecutor.CallerRunsPolicy} 回退为调用线程执行，
     * 起到天然限流作用，避免任务被静默丢弃。</p>
     */
    @Bean("knowledgeTaskExecutor")
    public Executor knowledgeTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("knowledge-async-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
