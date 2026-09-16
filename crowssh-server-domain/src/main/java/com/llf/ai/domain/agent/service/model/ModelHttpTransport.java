package com.llf.ai.domain.agent.service.model;

import org.springframework.http.client.reactive.HttpComponentsClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;

/** 模型流共享传输资源；构建器按调用隔离认证头，关闭后禁止重新创建客户端。 */
public final class ModelHttpTransport implements AutoCloseable {
    private HttpComponentsClientHttpConnector connector;
    private boolean closed;

    public synchronized WebClient.Builder builder() {
        if (closed) throw new IllegalStateException("模型 HTTP 传输已关闭");
        if (connector == null) {
            // 共享连接池不共享账号状态；重试由调用层决定，不跟随模型端点重定向。
            var client = org.apache.hc.client5.http.impl.async.HttpAsyncClients.custom()
                    .disableAutomaticRetries().disableRedirectHandling()
                    .disableCookieManagement().disableAuthCaching()
                    .setIOReactorConfig(org.apache.hc.core5.reactor.IOReactorConfig.custom()
                            .setIoThreadCount(2).build())
                    .build();
            connector = new HttpComponentsClientHttpConnector(client);
        }
        return WebClient.builder().clientConnector(connector);
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        if (connector != null) {
            try { connector.close(); }
            catch (java.io.IOException failure) { throw new IllegalStateException("模型 HTTP 传输关闭失败", failure); }
        }
    }
}
