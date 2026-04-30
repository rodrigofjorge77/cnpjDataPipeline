package org.projeto.cnpjdatapipeline.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
public class AsyncConfig {

    @Bean(name = "downloadExecutor")
    public Executor downloadExecutor(PipelineConfig config) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(config.getDownloadWorkers());
        executor.setMaxPoolSize(config.getDownloadWorkers());
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("download-");
        executor.initialize();
        return executor;
    }

    @Bean(name = "processExecutor")
    public Executor processExecutor(PipelineConfig config) {
        int workers = Math.max(1, config.getProcessWorkers());
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(workers);
        executor.setMaxPoolSize(workers);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("process-");
        executor.initialize();
        return executor;
    }
}
