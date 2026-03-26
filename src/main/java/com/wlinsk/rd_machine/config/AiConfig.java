package com.wlinsk.rd_machine.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({AiLlmProperties.class, AiTtsProperties.class})
public class AiConfig {

    @Bean
    ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    @Bean(destroyMethod = "shutdown")
    ExecutorService assistantStreamingExecutor() {
        return Executors.newCachedThreadPool();
    }
}
