package com.llf.ai.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SSE 流式执行器装配。
 *
 * <p>为什么用专用池而非复用共享 {@code threadPoolExecutor}（5000 队列）：ReAct 流式任务是
 * <b>长生命周期</b>（可达墙钟超时 10 分钟），若与短任务共享大队列，突发流量会在队列中堆积、
 * 迟迟得不到线程，造成队列饥饿。此处用 {@link SynchronousQueue} + {@code CallerRunsPolicy}：
 * 达到并发上限时新请求由调用线程（Tomcat 工作线程）就地承载，形成天然背压而非无界堆积。
 *
 * <p>心跳独立成 {@link ScheduledExecutorService} 小池，避免与流式任务争抢线程导致心跳延迟。
 *
 * @author llf
 */
@Slf4j
@Configuration
public class SseExecutorConfig {

    /** SSE 流式任务的最大并发数；超出后由调用线程就地执行（背压）。 */
    @Value("${crowssh.sse.max-concurrent-streams:50}")
    private int maxConcurrentStreams;

    /** 心跳调度线程数。 */
    @Value("${crowssh.sse.heartbeat-pool-size:2}")
    private int heartbeatPoolSize;

    /**
     * ReAct 流式任务执行器。核心=最大并发数、{@link SynchronousQueue} 不缓冲、
     * {@code CallerRunsPolicy} 提供背压；线程命名 {@code react-stream-*} 保留日志连续性。
     */
    @Bean(name = "sseStreamExecutor", destroyMethod = "shutdown")
    public ExecutorService sseStreamExecutor() {
        return new ThreadPoolExecutor(
                maxConcurrentStreams,
                maxConcurrentStreams,
                60L,
                TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                namedDaemonFactory("react-stream-"),
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    /**
     * SSE 心跳调度器。独立小池，线程命名 {@code sse-heartbeat-*}。
     */
    @Bean(name = "sseHeartbeatScheduler", destroyMethod = "shutdown")
    public ScheduledExecutorService sseHeartbeatScheduler() {
        return Executors.newScheduledThreadPool(
                heartbeatPoolSize, namedDaemonFactory("sse-heartbeat-"));
    }

    private ThreadFactory namedDaemonFactory(String prefix) {
        AtomicLong counter = new AtomicLong();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
