package com.app.recharge.recommendation.agent;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AdvisorConfig {

    /** Agent runs happen here, never on a request thread. Virtual threads: a run mostly waits for the model. */
    @Bean(destroyMethod = "shutdown")
    ExecutorService advisorExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
