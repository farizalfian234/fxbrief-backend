package com.fxbrief.notification.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Dedicated executor for outbound email so a slow or failing Resend call never
 * occupies a request-handling thread. Email is non-critical (PRD §10); the queue
 * is bounded and the rejection policy discards silently rather than propagating
 * back into the caller's flow.
 */
@Configuration
@EnableAsync
public class NotificationAsyncConfig {

    public static final String EMAIL_EXECUTOR = "emailExecutor";

    @Bean(EMAIL_EXECUTOR)
    public Executor emailExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("email-");
        executor.setRejectedExecutionHandler((r, e) -> { /* drop silently — email is non-critical */ });
        executor.initialize();
        return executor;
    }
}
