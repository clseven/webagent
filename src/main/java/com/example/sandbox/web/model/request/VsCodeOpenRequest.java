package com.example.sandbox.web.model.request;

/**
 * 请求在当前沙箱的 VSCode 工作台中打开文件并定位光标。
 *
 * @param path   沙箱绝对路径或相对于 {@code /home/gem} 的路径
 * @param line   目标行号，从 1 开始；为空时使用第 1 行
 * @param column 目标列号，从 1 开始；为空时使用第 1 列
 */
public record VsCodeOpenRequest(
        String path,
        Integer line,
        Integer column
) {
}
