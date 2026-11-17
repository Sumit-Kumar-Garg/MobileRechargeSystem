package com.app.recharge.common;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableAsync
@EnableScheduling
public class AppConfig {

    /**
     * Runs provisioning right after a payment commits. Bounded, so a burst can never exhaust the JVM.
     * Sizes come from app.provisioning.executor.* (PROVISIONING_EXECUTOR_* environment variables).
     */
    @Bean(name = "provisioningExecutor")
    public ThreadPoolTaskExecutor provisioningExecutor(
            @Value("${app.provisioning.executor.core-size:2}") int coreSize,
            @Value("${app.provisioning.executor.max-size:8}") int maxSize,
            @Value("${app.provisioning.executor.queue-capacity:200}") int queueCapacity) {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(coreSize);
        ex.setMaxPoolSize(maxSize);
        ex.setQueueCapacity(queueCapacity);
        ex.setThreadNamePrefix("provisioning-");
        ex.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy()); // the worker retries anything dropped
        ex.setWaitForTasksToCompleteOnShutdown(true);
        ex.setAwaitTerminationSeconds(20);
        return ex;
    }
}
