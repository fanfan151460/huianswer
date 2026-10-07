package com.lhsg.HuiAnswer.rag.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 启动时检查环境变量
 */
@Slf4j
@Component
public class ConfigValidator {

    @PostConstruct
    void check() {
        requireEnv("HUIANSWER_DB_PASSWORD");
        requireEnv("HUIANSWER_AI_API_KEY");
    }

    /**
     * 直接问进程环境，跟 yml 的默认值无关
     */
    private static void requireEnv(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException(
                    "环境变量 " + name + " 在当前进程环境里不存在，请检查启动方式");
        }
    }
}
