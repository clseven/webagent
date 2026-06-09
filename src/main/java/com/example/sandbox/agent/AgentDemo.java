package com.example.sandbox.agent;

//import java.time.Duration;
//import java.util.List;

/**
 * @deprecated 已废弃，SandboxAgent 仅用于沙箱生命周期管理
 *
 * <p>SandboxAgent 使用示例</p>
 *
 * @author example
 * @date 2026/05/11
 */
public class AgentDemo {

//    public static void main(String[] args) {
//        System.out.println("========================================");
//        System.out.println("    Sandbox Agent 演示");
//        System.out.println("========================================\n");
//
//        // 使用 try-with-resources 确保资源释放
//        try (SandboxAgent agent = SandboxAgent.builder()
//                .domain("localhost:8080")
//                .debug(true)
//                .timeout(Duration.ofMinutes(30))
//                .build()) {
//
//            System.out.println("沙箱 ID: " + agent.getSandboxId());
//            System.out.println("沙箱状态: " + agent.getSandboxInfo().getStatus().getState());
//            System.out.println();
//
//            // ========== 场景 1: 命令执行 ==========
//            demoCommandExecution(agent);
//
//            // ========== 场景 2: 文件操作 ==========
//            demoFileOperations(agent);
//
//            // ========== 场景 3: 代码执行 ==========
//            demoCodeExecution(agent);
//
//            // ========== 场景 4: 多步骤任务 ==========
//            demoMultiStepTask(agent);
//
//            System.out.println("\n========================================");
//            System.out.println("    演示完成，沙箱已自动清理");
//            System.out.println("========================================");
//
//        } catch (Exception e) {
//            System.err.println("错误: " + e.getMessage());
//            e.printStackTrace();
//        }
//    }
//
//    private static void demoCommandExecution(SandboxAgent agent) {
//        System.out.println(">>> 场景 1: 命令执行");
//        SandboxAgent.CommandResult result1 = agent.executeCommand("echo 'Hello World'");
//        System.out.println("    输出: " + result1.getStdout().trim());
//        SandboxAgent.CommandResult result2 = agent.executeCommand("ls -la /home | head -5");
//        System.out.println("    文件列表:\n" + indent(result2.getStdout(), 8));
//        SandboxAgent.CommandResult result3 = agent.executeCommand("test -f /etc/passwd && echo 'exists'");
//        System.out.println("    文件检测: " + result3.getStdout().trim() + " (退出码: " + result3.getExitCode() + ")");
//        System.out.println();
//    }
//
//    private static void demoFileOperations(SandboxAgent agent) {
//        System.out.println(">>> 场景 2: 文件操作");
//        String filePath = "/tmp/agent_demo.txt";
//        agent.writeFile(filePath, "这是 SandboxAgent 写入的内容\n第二行内容\n");
//        System.out.println("    文件已写入: " + filePath);
//        String content = agent.readFile(filePath);
//        System.out.println("    文件内容:\n" + indent(content, 8));
//        agent.deleteFiles(List.of(filePath));
//        System.out.println("    文件已删除");
//        System.out.println();
//    }
//
//    private static void demoCodeExecution(SandboxAgent agent) {
//        System.out.println(">>> 场景 3: 代码执行");
//        SandboxAgent.CommandResult pyResult = agent.runPython("import sys; print(f'Python {sys.version}')");
//        System.out.println("    Python 版本: " + pyResult.getStdout().trim());
//        String pythonScript = """
//                import json
//                data = {"name": "SandboxAgent", "version": "1.0"}
//                print(json.dumps(data, indent=2))
//                """;
//        SandboxAgent.CommandResult pyScriptResult = agent.runPythonScript("demo.py", pythonScript);
//        System.out.println("    Python 脚本输出:\n" + indent(pyScriptResult.getStdout(), 8));
//        SandboxAgent.CommandResult jsResult = agent.runJavaScript("console.log('Node.js:', process.version)");
//        System.out.println("    Node.js 版本: " + jsResult.getStdout().trim());
//        System.out.println();
//    }
//
//    private static void demoMultiStepTask(SandboxAgent agent) {
//        System.out.println(">>> 场景 4: 多步骤任务（模拟 Agent 工作流）");
//        agent.executeCommand("mkdir -p /tmp/agent_workspace");
//        System.out.println("    [步骤 1] 创建工作目录");
//        agent.writeFile("/tmp/agent_workspace/data.csv", "name,score\nAlice,85\nBob,92\nCharlie,78\n");
//        System.out.println("    [步骤 2] 写入数据文件");
//        String analysisScript = """
//                import csv
//                with open('/tmp/agent_workspace/data.csv') as f:
//                    reader = csv.DictReader(f)
//                    scores = [int(row['score']) for row in reader]
//                print(f'平均分: {sum(scores)/len(scores):.2f}')
//                print(f'最高分: {max(scores)}')
//                print(f'最低分: {min(scores)}')
//                """;
//        agent.writeFile("/tmp/agent_workspace/analyze.py", analysisScript, 755);
//        System.out.println("    [步骤 3] 编写分析脚本");
//        SandboxAgent.CommandResult result = agent.executeCommand("python3 /tmp/agent_workspace/analyze.py");
//        System.out.println("    [步骤 4] 执行分析结果:\n" + indent(result.getStdout(), 12));
//        agent.executeCommand("rm -rf /tmp/agent_workspace");
//        System.out.println("    [步骤 5] 清理工作目录");
//        System.out.println();
//    }
//
//    private static String indent(String text, int spaces) {
//        String indentStr = " ".repeat(spaces);
//        return text.lines()
//                .map(line -> indentStr + line)
//                .reduce((a, b) -> a + "\n" + b)
//                .orElse("");
//    }
}
