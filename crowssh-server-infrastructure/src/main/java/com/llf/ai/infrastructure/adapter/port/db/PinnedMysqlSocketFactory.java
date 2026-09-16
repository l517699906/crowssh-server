package com.llf.ai.infrastructure.adapter.port.db;

import com.google.common.net.InetAddresses;
import com.mysql.cj.conf.PropertySet;
import com.mysql.cj.protocol.StandardSocketFactory;

import java.io.Closeable;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 证书身份保留在驱动逻辑 host；实际拨号仅使用服务端注册的固定 IP。 */
public final class PinnedMysqlSocketFactory extends StandardSocketFactory {
    static final String ROUTE_PROPERTY = "crowsshRouteId";
    private static final Map<String, Route> ROUTES = new ConcurrentHashMap<>();
    private Route route;
    private DbPhysicalConnectionQuota.Lease permit;

    static String register(String address, int port, String ownerId,
                           DbPhysicalConnectionQuota quota, DbPhysicalConnectionQuota.Kind kind) {
        String ip = InetAddresses.toAddrString(InetAddresses.forString(address));
        if (port < 1 || port > 65535) throw new IllegalArgumentException("数据库拨号端口不合法");
        String id = UUID.randomUUID().toString();
        ROUTES.put(id, new Route(ip, port, ownerId, quota, kind));
        return id;
    }

    static void unregister(String id) {
        Route registered = id == null ? null : ROUTES.remove(id);
        if (registered != null) registered.abort();
    }

    static void abort(String id) {
        Route registered = ROUTES.get(id);
        if (registered == null) return;
        registered.abort();
    }

    @Override
    protected Socket createSocket(PropertySet properties) {
        synchronized (route) {
            if (!route.active) throw new IllegalStateException("数据库拨号租约无效");
            DbPhysicalConnectionQuota.Lease socketPermit = permit;
            Route socketRoute = route;
            Socket socket = new Socket() {
                @Override public synchronized void close() throws IOException {
                    try { super.close(); }
                    finally { socketPermit.close(); socketRoute.sockets.remove(this); }
                }
            };
            route.sockets.add(socket);
            return socket;
        }
    }

    @Override
    public <T extends Closeable> T connect(String hostname, int portNumber, PropertySet properties,
                                           int loginTimeout) throws IOException {
        var property = properties.getStringProperty(ROUTE_PROPERTY);
        route = property == null ? null : ROUTES.get(property.getValue());
        if (route == null) throw new SocketException("数据库拨号租约无效");
        synchronized (route) {
            if (!route.active) throw new SocketException("数据库拨号租约无效");
            try {
                permit = route.quota.acquire(route.ownerId,
                        route.primaryClaimed ? DbPhysicalConnectionQuota.Kind.CONTROL : route.kind);
                route.primaryClaimed = true;
            } catch (IllegalStateException e) {
                throw new SocketException(e.getMessage());
            }
        }
        try {
            // 只解析此 IP，不重新解析逻辑证书域名；TLS 仍使用 SocketConnection.host。
            return super.connect(route.address, route.port, properties, loginTimeout);
        } catch (IOException | RuntimeException e) {
            if (rawSocket != null) try { rawSocket.close(); } catch (IOException ignored) { }
            permit.close();
            throw e;
        }
    }

    private static final class Route {
        final String address;
        final int port;
        final String ownerId;
        final DbPhysicalConnectionQuota quota;
        final DbPhysicalConnectionQuota.Kind kind;
        final java.util.Set<Socket> sockets = ConcurrentHashMap.newKeySet();
        boolean active = true;
        boolean primaryClaimed;

        Route(String address, int port, String ownerId, DbPhysicalConnectionQuota quota,
              DbPhysicalConnectionQuota.Kind kind) {
            this.address = address; this.port = port; this.ownerId = ownerId;
            this.quota = quota; this.kind = kind;
        }

        synchronized void abort() {
            active = false;
            for (Socket socket : sockets) {
                try { socket.close(); } catch (IOException ignored) { }
            }
        }
    }
}
