package com.wlinsk.rd_machine.basic.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class WebMvcConfig {

    private static final Duration MVC_STREAM_TIMEOUT = Duration.ofMinutes(5);

    @Bean(destroyMethod = "shutdown")
    ExecutorService mvcStreamingExecutor() {
        return Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("mvc-stream-", 0).factory());
    }

    @Bean
    AsyncTaskExecutor mvcStreamingTaskExecutor(ExecutorService mvcStreamingExecutor) {
        return new TaskExecutorAdapter(mvcStreamingExecutor);
    }

    @Bean
    WebMvcConfigurer webMvcConfigurer(AsyncTaskExecutor mvcStreamingTaskExecutor) {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/**")
                        .allowedHeaders("*")
                        .allowedOriginPatterns("*")
                        .allowCredentials(true)
                        .allowedMethods("*")
                        .maxAge(3600);
            }

            @Override
            public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
                configurer.setTaskExecutor(mvcStreamingTaskExecutor);
                configurer.setDefaultTimeout(MVC_STREAM_TIMEOUT.toMillis());
            }
        };
    }
}
