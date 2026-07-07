package com.example.sandbox.web.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Agent 配置属性
 *
 * @author example
 * @date 2026/05/14
 */
@Setter
@Getter
@Component
@ConfigurationProperties(prefix = "agent")
public class AgentConfigProperties {

    private Sandbox sandbox = new Sandbox();
    private Skill skill = new Skill();
    private Llm llm = new Llm();
    private Storage storage = new Storage();
    private Hook hook = new Hook();

    /**
     * Hook 层开关配置。
     */
    @Setter
    @Getter
    public static class Hook {
        /**
         * 是否启用文件状态检查（State Checks）。出问题可置 false 立即恢复无校验。
         * 默认开启：单线程下低开销待命，并发落地后防 TOCTOU。
         */
        private boolean stateCheckEnabled = true;

        /**
         * 是否启用工具并发执行。出问题可置 false 退化为串行（仍遍历 tool_calls 列表）。
         * 默认开启：READ 类并发、WRITE/EXCLUSIVE 串行。
         */
        private boolean concurrentToolExecutionEnabled = true;
    }

    @Setter
    @Getter
    public static class Sandbox {
        private String domain = "localhost:8080";
        private String image = "sandbox-registry.cn-zhangjiakou.cr.aliyuncs.com/opensandbox/code-interpreter:v1.0.2";
        private String timeout = "PT30M";
        private String readyTimeout = "PT120S";
        private String sandboxTimeout = "P7D";

    }

    @Setter
    @Getter
    public static class Skill {
        private String directory = ".claude/skills";

    }

    @Setter
    @Getter
    public static class Llm {
        private Planner planner = new Planner();
        private Executor executor = new Executor();
        private Vision vision = new Vision();

        @Setter
        @Getter
        public static class Planner {
            private String apiUrl = "https://api.deepseek.com";
            private String apiKey = "";
            private String model = "deepseek-v4-flash";

        }

        @Setter
        @Getter
        public static class Executor {
            private String apiUrl = "https://api.deepseek.com";
            private String apiKey = "";
            private String model = "deepseek-v4-flash";

            /**
             * 是否为执行器模型启用思考模式（仅 DeepSeek 专有参数，切换回 DeepSeek 时使用）。
             * Agnes 不使用此字段。
             */
            private boolean thinkingEnabled = false;

        }

        @Setter
        @Getter
        public static class Vision {
            private String apiUrl = "https://apihub.agnes-ai.com/v1";
            private String apiKey = "";
            private String model = "agnes-2.0-flash";

        }
    }

    @Setter
    @Getter
    public static class Storage {
        private String type = "local";
        private Local local = new Local();
        private Oss oss = new Oss();

        @Setter
        @Getter
        public static class Local {
            private String basePath = "./uploads";

        }

        @Setter
        @Getter
        public static class Oss {
            private String endpoint = "";
            private String bucket = "";
            private String accessKey = "";
            private String secretKey = "";

        }
    }
}
