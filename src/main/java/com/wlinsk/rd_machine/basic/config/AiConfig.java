package com.wlinsk.rd_machine.basic.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({AiLlmProperties.class, AiTtsProperties.class, CosyVoiceTtsProperties.class})
public class AiConfig {

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    @Bean(destroyMethod = "shutdown")
    public ExecutorService assistantStreamingExecutor() {
        return Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("assistant-stream-", 0).factory());
    }

    @Bean(destroyMethod = "shutdown")
    public ExecutorService ttsStreamingExecutor() {
        return Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("tts-stream-", 0).factory());
    }
}
