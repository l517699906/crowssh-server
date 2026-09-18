package com.llf.ai.domain.agent.service.armory.matter.tools;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** 派发池与 SSE/心跳池隔离；过载立即拒绝，不在父调用线程执行子任务。 */
@Configuration
public class SubAgentExecutorConfiguration {
    @Bean(name = "subAgentExecutor", destroyMethod = "shutdownNow")
    public ExecutorService subAgentExecutor() {
        AtomicInteger sequence = new AtomicInteger();
        return new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(32), task -> {
            Thread thread = new Thread(task, "sub-agent-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }
}
