package com.example.sandbox.web.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 后台任务管理器 — 统一调度慢操作的后台执行。
 *
 * <h3>设计要点</h3>
 * <ul>
 *   <li>接收 {@link Supplier} 闭包而非直接调 Tool，超时/异常已在调用方包好</li>
 *   <li>{@code newFixedThreadPool(5)} + daemon 线程复用，Semaphore 做准入限流</li>
 *   <li>{@link Future} 追踪实现真取消</li>
 *   <li>{@link ConcurrentLinkedQueue} 无锁通知投递，Agent 循环主动 poll</li>
 * </ul>
 */
@Component
public class BackgroundTaskManager {

    private static final Logger log = LoggerFactory.getLogger(BackgroundTaskManager.class);

    /** 固定 5 个 daemon 线程，复用不销毁 */
    private final ExecutorService executor = Executors.newFixedThreadPool(5, r -> {
        Thread t = new Thread(r, "bg-worker");
        t.setDaemon(true);
        return t;
    });

    /** 并发准入：超限回退同步执行 */
    private final Semaphore semaphore = new Semaphore(5);

    /** 通知队列：sessionId → 已完成任务的通知文本 */
    private final ConcurrentHashMap<String, ConcurrentLinkedQueue<String>>
            notifications = new ConcurrentHashMap<>();

    /** Future 追踪：sessionId → (bgId → Future)，用于 cancelAll() 真取消 */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Future<?>>>
            futures = new ConcurrentHashMap<>();

    /** 活跃计数：sessionId → 运行中任务数，用于 isPending() */
    private final ConcurrentHashMap<String, AtomicInteger>
            activeCounts = new ConcurrentHashMap<>();

    private final AtomicInteger counter = new AtomicInteger(0);

    /**
     * 启动后台任务。
     *
     * @param sessionId 会话 ID
     * @param task      闭包：已包含超时和异常包装的执行逻辑
     * @param label     日志标签
     * @return bg_id，或 null 表示并发已满需回退同步
     */
    public String start(String sessionId, Supplier<String> task, String label) {
        if (!semaphore.tryAcquire()) {
            log.warn("后台并发已满，回退同步: {}", label);
            return null;
        }

        String bgId = "bg_" + String.format("%04d", counter.incrementAndGet());

        activeCounts.computeIfAbsent(sessionId, k -> new AtomicInteger()).incrementAndGet();

        Future<?> future = executor.submit(() -> {
            try {
                String result = task.get();
                String shortResult = result.length() > 300
                        ? result.substring(0, 300) + "..." : result;
                String notification = String.format(
                        "<task_notification>\n" +
                        "  <task_id>%s</task_id>\n" +
                        "  <status>completed</status>\n" +
                        "  <summary>%s</summary>\n" +
                        "</task_notification>", bgId, shortResult);

                notifications.computeIfAbsent(sessionId, k -> new ConcurrentLinkedQueue<>())
                        .add(notification);
                log.info("后台任务完成: {}, label={}", bgId, label);
            } catch (Exception e) {
                String errorNote = String.format(
                        "<task_notification>\n" +
                        "  <task_id>%s</task_id>\n" +
                        "  <status>failed</status>\n" +
                        "  <summary>%s</summary>\n" +
                        "</task_notification>", bgId, e.getMessage());
                notifications.computeIfAbsent(sessionId, k -> new ConcurrentLinkedQueue<>())
                        .add(errorNote);
                log.error("后台任务失败: {}, label={}", bgId, e);
            } finally {
                semaphore.release();
                AtomicInteger count = activeCounts.get(sessionId);
                if (count != null) {
                    count.decrementAndGet();
                }
            }
        });

        futures.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>())
                .put(bgId, future);

        log.info("后台任务已启动: {}, label={}", bgId,
                label.length() > 80 ? label.substring(0, 80) + "..." : label);
        return bgId;
    }

    /**
     * 收集已完成任务的通知文本，拼接为一条消息。
     */
    public String collect(String sessionId) {
        ConcurrentLinkedQueue<String> queue = notifications.get(sessionId);
        if (queue == null || queue.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        String n;
        while ((n = queue.poll()) != null) {
            if (!sb.isEmpty()) {
                sb.append("\n\n");
            }
            sb.append(n);
        }
        return sb.toString();
    }

    /**
     * 该会话是否有未完成的后台任务。
     */
    public boolean isPending(String sessionId) {
        AtomicInteger count = activeCounts.get(sessionId);
        return count != null && count.get() > 0;
    }

    /**
     * 阻塞等待所有后台任务完成，返回期间收集的通知。
     */
    public String awaitPending(String sessionId, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (isPending(sessionId) && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return collect(sessionId);
    }

    /**
     * 取消会话所有后台任务并清理资源。
     */
    public void cancelAll(String sessionId) {
        ConcurrentHashMap<String, Future<?>> sessionFutures = futures.remove(sessionId);
        if (sessionFutures != null) {
            sessionFutures.values().forEach(f -> f.cancel(true));
            log.info("已取消后台任务: session={}, count={}", sessionId, sessionFutures.size());
        }
        notifications.remove(sessionId);
        activeCounts.remove(sessionId);
    }
}
