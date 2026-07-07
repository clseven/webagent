package com.example.sandbox.web.service;

/**
 * 工具副作用类型 — 供并发调度器决定能否并行执行。
 *
 * <p>调度规则：READ 类互不干扰可并发；WRITE/EXCLUSIVE 串行且与 READ 互斥。
 * 默认按最保守的 {@link #EXCLUSIVE} 对待，只把确认无副作用的工具显式标 {@link #READ}，
 * 避免误把有副作用的工具当读并发导致数据竞争。</p>
 */
public enum ToolSideEffect {

    /** 纯读，不改共享状态（如 web_search、web_fetch、read_file、grep）。可并发。 */
    READ,

    /** 改文件/沙箱状态（如 write_file、file_replace）。串行，且与 READ 互斥。 */
    WRITE,

    /** 独占：改环境或有全局副作用（如 execute_command、git 操作）。完全串行。 */
    EXCLUSIVE
}
