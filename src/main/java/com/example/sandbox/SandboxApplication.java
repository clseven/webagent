package com.example.sandbox;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Sandbox Agent 应用启动类
 *
 * @author example
 * @date 2026/05/14
 */
@SpringBootApplication
@EnableAsync
@EnableScheduling
public class SandboxApplication {

    public static void main(String[] args) {
        SpringApplication.run(SandboxApplication.class, args);
    }
}
