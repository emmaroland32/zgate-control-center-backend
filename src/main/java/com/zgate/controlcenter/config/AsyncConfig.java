package com.zgate.controlcenter.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * The executor behind {@code @Async} — which in this product means the audit trail.
 *
 * <p>The default executor has an unbounded queue and a silent uncaught-exception handler, so a
 * burst of sign-in attempts could grow the heap without limit, and an audit row that failed to
 * write (a value too long for its column, a database blip) vanished with nothing but a DEBUG line.
 * This pool is bounded, and when full the caller writes the row itself rather than dropping it.
 * Failures are logged at ERROR with the method that failed, so a lost audit row is visible.
 */
@Configuration
@Slf4j
public class AsyncConfig implements AsyncConfigurer {

    @Override
    public Executor getAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("cc-async-");
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(2000);
        // Back-pressure instead of loss: the request thread does the write when the queue is full.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (ex, method, params) ->
            log.error("Async task {}.{} failed: {}", method.getDeclaringClass().getSimpleName(),
                      method.getName(), ex.toString(), ex);
    }
}
