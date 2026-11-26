package io.github.headnose.dsp.settlement.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
public class ImportExecutorConfig {

    @Bean(name = "loanImportExecutor")
    public Executor loanImportExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("loan-import-");
        // O2: the trace context is a ThreadLocal of the request thread. Without this, every loan-service call
        // made on a loan-import- thread started a trace of its own (seen in Jaeger). The decorator snapshots
        // the context when the task is submitted and restores it in the worker thread while the task runs
        // (also puts traceId/spanId into the worker's log lines).
        executor.setTaskDecorator(new ContextPropagatingTaskDecorator());
        executor.initialize();
        return executor;
    }
}