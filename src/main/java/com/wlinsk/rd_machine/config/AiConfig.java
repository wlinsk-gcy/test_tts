package com.wlinsk.rd_machine.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({AiLlmProperties.class, AiTtsProperties.class})
public class AiConfig {

    private static final int MAX_CONCURRENT_ASSISTANT_SESSIONS = 20;
    private static final int ASSISTANT_QUEUE_CAPACITY = 20;
    private static final int TTS_QUEUE_CAPACITY = 20;

    @Bean
    ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    @Bean(destroyMethod = "shutdown")
    ExecutorService assistantStreamingExecutor() {
        return newBoundedExecutor("assistant-stream", ASSISTANT_QUEUE_CAPACITY);
    }

    @Bean(destroyMethod = "shutdown")
    ExecutorService ttsStreamingExecutor() {
        return newBoundedExecutor("tts-stream", TTS_QUEUE_CAPACITY);
    }

    private ExecutorService newBoundedExecutor(String threadNamePrefix, int queueCapacity) {
        return new ThreadPoolExecutor(
                MAX_CONCURRENT_ASSISTANT_SESSIONS,
                MAX_CONCURRENT_ASSISTANT_SESSIONS,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                namedThreadFactory(threadNamePrefix)
        );
    }

    private ThreadFactory namedThreadFactory(String prefix) {
        AtomicInteger sequence = new AtomicInteger(1);
        return runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName(prefix + "-" + sequence.getAndIncrement());
            return thread;
        };
    }
}
