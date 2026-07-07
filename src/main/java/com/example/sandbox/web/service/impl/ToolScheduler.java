package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.model.llm.LlmToolCall;
import com.example.sandbox.web.service.ToolSideEffect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.function.Function;

/**
 * 工具调用调度器 — 一轮多个 tool_call 的分类并发执行。
 *
 * <h3>调度规则</h3>
 * <ul>
 *   <li>{@link ToolSideEffect#READ} 类互不干扰，可并发（受并发数限流）。</li>
 *   <li>{@link ToolSideEffect#WRITE} / {@link ToolSideEffect#EXCLUSIVE} 串行，且与 READ 互斥。</li>
 *   <li>结果按模型给出的 tool_call 原始顺序对齐返回，与实际完成顺序无关。</li>
 * </ul>
 *
 * <h3>读写互斥的实现</h3>
 * <p>按原始顺序扫描：累积连续的 READ 批次并发执行，遇到 WRITE/EXCLUSIVE 时先等待当前 READ
 * 批次全部完成（flush），再单独串行执行该写工具。这样既保证读写不重叠，又保持相对顺序。</p>
 *
 * <h3>无状态</h3>
 * <p>本类不持有会话状态，每次调用现用现建线程池并在结束时关闭；单工具异常由任务函数内部消化，
 * 调度器只负责编排。</p>
 */
public class ToolScheduler {

    private static final Logger log = LoggerFactory.getLogger(ToolScheduler.class);

    /** READ 类默认最大并发数。 */
    static final int DEFAULT_READ_CONCURRENCY = 3;

    /** READ 类最大并发数。 */
    private final int readConcurrency;

    /** 是否启用并发；false 时退化为按原始顺序串行执行（仍遍历列表，但不并发）。 */
    private final boolean enabled;

    /**
     * 用默认并发数创建调度器（启用并发）。
     */
    public ToolScheduler() {
        this(DEFAULT_READ_CONCURRENCY, true);
    }

    /**
     * 创建调度器。
     *
     * @param readConcurrency READ 类最大并发数（至少 1）
     */
    public ToolScheduler(int readConcurrency) {
        this(readConcurrency, true);
    }

    /**
     * 创建调度器，可指定是否启用并发。
     *
     * @param readConcurrency READ 类最大并发数（至少 1）
     * @param enabled         false 时退化为串行遍历（回滚开关）
     */
    public ToolScheduler(int readConcurrency, boolean enabled) {
        this.readConcurrency = Math.max(1, readConcurrency);
        this.enabled = enabled;
    }

    /**
     * 按副作用分类执行一批工具调用，结果按原始顺序返回。
     *
     * @param calls      本轮工具调用（按模型给出的顺序）
     * @param classifier 工具调用 → 副作用类型
     * @param task       单个工具调用的执行逻辑（含 Pre/Post Hook 与实际执行，异常应内部消化）
     * @param <R>        单调用结果类型
     * @return 与 {@code calls} 等长、同序的结果列表
     */
    public <R> List<R> execute(List<LlmToolCall> calls,
                               Function<LlmToolCall, ToolSideEffect> classifier,
                               Function<LlmToolCall, R> task) {
        int n = calls.size();
        List<R> results = new ArrayList<>(java.util.Collections.nCopies(n, null));
        if (n == 0) {
            return results;
        }
        if (n == 1) {
            results.set(0, task.apply(calls.get(0)));
            return results;
        }

        // 回滚模式：退化为按原始顺序串行执行（不并发），仍保证结果对齐
        if (!enabled) {
            for (int i = 0; i < n; i++) {
                results.set(i, task.apply(calls.get(i)));
            }
            return results;
        }

        ExecutorService pool = Executors.newFixedThreadPool(readConcurrency);
        Semaphore limiter = new Semaphore(readConcurrency);
        try {
            List<CompletableFuture<Void>> pendingReads = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                final int index = i;
                final LlmToolCall call = calls.get(i);
                ToolSideEffect effect = safeClassify(classifier, call);

                if (effect == ToolSideEffect.READ) {
                    // 累积到并发批次
                    pendingReads.add(CompletableFuture.runAsync(() -> {
                        try {
                            limiter.acquire();
                            try {
                                results.set(index, task.apply(call));
                            } finally {
                                limiter.release();
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            log.warn("READ 工具并发执行被中断: {}", call.name());
                        }
                    }, pool));
                } else {
                    // WRITE/EXCLUSIVE：先等待已累积的 READ 批次完成（读写互斥），再串行执行
                    flush(pendingReads);
                    results.set(index, task.apply(call));
                }
            }
            flush(pendingReads);
        } finally {
            pool.shutdown();
        }
        return results;
    }

    /**
     * 等待并清空当前累积的 READ 批次。
     *
     * @param pendingReads 待完成的 READ 任务
     */
    private void flush(List<CompletableFuture<Void>> pendingReads) {
        if (pendingReads.isEmpty()) {
            return;
        }
        CompletableFuture.allOf(pendingReads.toArray(new CompletableFuture[0])).join();
        pendingReads.clear();
    }

    /**
     * 分类，异常或返回 null 时保守视为 EXCLUSIVE（完全串行），避免误并发。
     *
     * @param classifier 分类函数
     * @param call       工具调用
     * @return 副作用类型
     */
    private ToolSideEffect safeClassify(Function<LlmToolCall, ToolSideEffect> classifier, LlmToolCall call) {
        try {
            ToolSideEffect effect = classifier.apply(call);
            return effect != null ? effect : ToolSideEffect.EXCLUSIVE;
        } catch (Exception e) {
            log.warn("工具副作用分类失败，保守串行: {}", call.name(), e);
            return ToolSideEffect.EXCLUSIVE;
        }
    }
}
