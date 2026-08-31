package com.llf.ai.trigger.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llf.ai.domain.ssh.service.ISshTerminalService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.SubProtocolCapable;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SSH 交互终端 WebSocket 数据面。
 */
@Component
public class TerminalWebSocketHandler extends TextWebSocketHandler implements SubProtocolCapable {

    static final long OUTPUT_POLL_MILLIS = 15L;
    static final long HEARTBEAT_MILLIS = 15_000L;
    static final long DISCONNECTED_RETENTION_MILLIS = 60_000L;
    static final int MAX_REPLAY_FRAMES = 256;
    static final int MAX_REPLAY_CHARS = 512 * 1024;
    static final int MAX_OUTPUT_CHUNK_CHARS = 32 * 1024;
    static final int MAX_INPUT_CHARS = 64 * 1024;

    private static final CloseStatus REPLACED = new CloseStatus(4001, "replaced");
    private static final CloseStatus TERMINAL_CLOSED = new CloseStatus(4002, "terminal closed");
    private static final Logger DIAGNOSTIC =
            LoggerFactory.getLogger("CROWSSH_TERMINAL_DIAGNOSTIC");

    private final ISshTerminalService terminalService;
    private final ObjectMapper objectMapper;
    private final ScheduledExecutorService scheduler;
    private final Map<String, ChannelState> channels = new ConcurrentHashMap<>();
    private final Map<String, SocketBinding> sockets = new ConcurrentHashMap<>();

    public TerminalWebSocketHandler(ISshTerminalService terminalService, ObjectMapper objectMapper) {
        this.terminalService = terminalService;
        this.objectMapper = objectMapper;
        this.scheduler = Executors.newScheduledThreadPool(4, new TerminalThreadFactory());
    }

    @Override
    public List<String> getSubProtocols() {
        return List.of(TerminalWebSocketHandshakeInterceptor.PROTOCOL);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        Object attribute = session.getAttributes().get(
                TerminalWebSocketHandshakeInterceptor.GRANT_ATTRIBUTE);
        if (!(attribute instanceof TerminalWebSocketTicketService.TicketGrant grant)
                || !terminalService.sessionExists(grant.ownerId(), grant.sessionId())) {
            DIAGNOSTIC.warn("event=ws_rejected socketId={} reason=invalid_session", session.getId());
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }

        WebSocketSession decorated = new ConcurrentWebSocketSessionDecorator(
                session, 10_000, MAX_REPLAY_CHARS * 2);
        ChannelState state = channels.compute(grant.sessionId(), (sessionId, existing) -> {
            if (existing != null && existing.ownerId.equals(grant.ownerId())) {
                return existing;
            }
            return new ChannelState(grant.ownerId(), grant.sessionId());
        });
        sockets.put(session.getId(), new SocketBinding(state, decorated));
        state.attach(decorated, grant.resumeAfter());
        DIAGNOSTIC.info("event=ws_connected sessionId={} socketId={} resumeAfter={}",
                grant.sessionId(), session.getId(), grant.resumeAfter());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        SocketBinding binding = sockets.get(session.getId());
        if (binding == null) {
            return;
        }
        try {
            JsonNode frame = objectMapper.readTree(message.getPayload());
            String type = frame.path("type").asText("");
            switch (type) {
                case "input" -> handleInput(binding.state(), frame);
                case "resize" -> handleResize(binding.state(), frame);
                case "ack" -> binding.state().acknowledge(frame.path("serverSeq").asLong(-1L));
                case "ping" -> binding.state().send(Map.of(
                        "type", "pong",
                        "timestamp", frame.path("timestamp").asLong(System.currentTimeMillis())));
                case "pong" -> {
                    // 客户端仍在线；readTerminal 会同步刷新领域会话活跃时间。
                }
                default -> binding.state().send(Map.of(
                        "type", "error",
                        "code", "UNSUPPORTED_FRAME",
                        "message", "不支持的终端帧类型"));
            }
        } catch (Exception exception) {
            DIAGNOSTIC.warn("event=ws_frame_error sessionId={} socketId={} errorType={} message={}",
                    binding.state().sessionId, session.getId(), exception.getClass().getSimpleName(),
                    sanitize(exception.getMessage()));
            binding.state().send(Map.of(
                    "type", "error",
                    "code", "INVALID_FRAME",
                    "message", "终端消息格式无效"));
        }
    }

    private void handleInput(ChannelState state, JsonNode frame) {
        long clientSeq = frame.path("clientSeq").asLong(-1L);
        String data = frame.path("data").asText(null);
        if (clientSeq < 0 || data == null || data.length() > MAX_INPUT_CHARS) {
            throw new IllegalArgumentException("输入帧参数无效");
        }
        if (!state.acceptClientSequence(clientSeq)) {
            return;
        }
        terminalService.writeTerminal(state.ownerId, state.sessionId, data);
        state.send(Map.of("type", "input_ack", "clientSeq", clientSeq));
    }

    private void handleResize(ChannelState state, JsonNode frame) {
        int cols = frame.path("cols").asInt(0);
        int rows = frame.path("rows").asInt(0);
        if (cols < 1 || cols > 1000 || rows < 1 || rows > 500) {
            throw new IllegalArgumentException("终端尺寸超出允许范围");
        }
        terminalService.resizeTerminal(state.ownerId, state.sessionId, cols, rows);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        SocketBinding binding = sockets.get(session.getId());
        if (binding == null) {
            return;
        }
        DIAGNOSTIC.warn("event=ws_transport_error sessionId={} socketId={} errorType={} message={}",
                binding.state().sessionId, session.getId(), exception.getClass().getSimpleName(),
                sanitize(exception.getMessage()));
        binding.state().detach(binding.socket());
        closeQuietly(binding.socket(), CloseStatus.SERVER_ERROR);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        SocketBinding binding = sockets.remove(session.getId());
        if (binding == null) {
            return;
        }
        binding.state().detach(binding.socket());
        DIAGNOSTIC.info("event=ws_disconnected sessionId={} socketId={} closeCode={} reason={}",
                binding.state().sessionId, session.getId(), status.getCode(),
                sanitize(status.getReason()));
    }

    @PreDestroy
    void shutdown() {
        channels.values().forEach(ChannelState::shutdown);
        scheduler.shutdownNow();
    }

    private final class ChannelState {
        private final String ownerId;
        private final String sessionId;
        private final ArrayDeque<OutputFrame> replay = new ArrayDeque<>();
        private WebSocketSession socket;
        private ScheduledFuture<?> pumpTask;
        private long nextServerSeq;
        private long lastClientSeq = -1L;
        private long lastSendAt = System.currentTimeMillis();
        private long detachedAt;
        private int replayChars;
        private int consecutiveReadErrors;

        private ChannelState(String ownerId, String sessionId) {
            this.ownerId = ownerId;
            this.sessionId = sessionId;
        }

        synchronized void attach(WebSocketSession nextSocket, long resumeAfter) {
            if (socket != null && socket.isOpen()) {
                closeQuietly(socket, REPLACED);
            }
            socket = nextSocket;
            detachedAt = 0L;
            boolean replayTruncated = !replay.isEmpty()
                    && resumeAfter < replay.peekFirst().sequence() - 1L;
            send(Map.of(
                    "type", "ready",
                    "sessionId", sessionId,
                    "serverSeq", nextServerSeq,
                    "replayTruncated", replayTruncated));
            for (OutputFrame outputFrame : new ArrayList<>(replay)) {
                if (outputFrame.sequence() > resumeAfter) {
                    sendOutput(outputFrame);
                }
            }
            if (pumpTask == null || pumpTask.isDone()) {
                pumpTask = scheduler.scheduleWithFixedDelay(
                        this::pump, 0L, OUTPUT_POLL_MILLIS, TimeUnit.MILLISECONDS);
            }
        }

        synchronized void detach(WebSocketSession candidate) {
            if (socket != candidate) {
                return;
            }
            socket = null;
            detachedAt = System.currentTimeMillis();
        }

        synchronized boolean acceptClientSequence(long sequence) {
            if (sequence <= lastClientSeq) {
                send(Map.of("type", "input_ack", "clientSeq", sequence));
                return false;
            }
            lastClientSeq = sequence;
            return true;
        }

        synchronized void acknowledge(long sequence) {
            if (sequence < 0) {
                return;
            }
            while (!replay.isEmpty() && replay.peekFirst().sequence() <= sequence) {
                replayChars -= replay.removeFirst().data().length();
            }
        }

        synchronized void pump() {
            long now = System.currentTimeMillis();
            if (socket == null && detachedAt > 0
                    && now - detachedAt >= DISCONNECTED_RETENTION_MILLIS) {
                shutdown();
                try {
                    terminalService.closeTerminal(ownerId, sessionId);
                } catch (RuntimeException exception) {
                    DIAGNOSTIC.warn("event=ws_retention_close_error sessionId={} errorType={} message={}",
                            sessionId, exception.getClass().getSimpleName(),
                            sanitize(exception.getMessage()));
                }
                channels.remove(sessionId, this);
                DIAGNOSTIC.info("event=ws_retention_expired sessionId={} unackedFrames={}",
                        sessionId, replay.size());
                return;
            }
            try {
                String output = terminalService.readTerminal(ownerId, sessionId);
                consecutiveReadErrors = 0;
                if (output != null && !output.isEmpty()) {
                    appendOutput(output);
                } else if (socket != null && now - lastSendAt >= HEARTBEAT_MILLIS) {
                    send(Map.of("type", "ping", "timestamp", now));
                }
            } catch (IllegalArgumentException exception) {
                send(Map.of("type", "terminal_closed", "message", exception.getMessage()));
                closeQuietly(socket, TERMINAL_CLOSED);
                shutdown();
                channels.remove(sessionId, this);
                DIAGNOSTIC.warn("event=terminal_invalid sessionId={} message={}",
                        sessionId, sanitize(exception.getMessage()));
            } catch (RuntimeException exception) {
                consecutiveReadErrors++;
                DIAGNOSTIC.warn("event=terminal_read_error sessionId={} attempt={} errorType={} message={}",
                        sessionId, consecutiveReadErrors, exception.getClass().getSimpleName(),
                        sanitize(exception.getMessage()));
                if (consecutiveReadErrors >= 3 && socket != null) {
                    send(Map.of("type", "error", "code", "READ_FAILED",
                            "message", "终端输出读取异常"));
                    closeQuietly(socket, CloseStatus.SERVER_ERROR);
                }
            }
        }

        private void appendOutput(String output) {
            for (int offset = 0; offset < output.length(); offset += MAX_OUTPUT_CHUNK_CHARS) {
                String chunk = output.substring(
                        offset, Math.min(output.length(), offset + MAX_OUTPUT_CHUNK_CHARS));
                OutputFrame frame = new OutputFrame(++nextServerSeq, chunk);
                replay.addLast(frame);
                replayChars += chunk.length();
                while (replay.size() > MAX_REPLAY_FRAMES || replayChars > MAX_REPLAY_CHARS) {
                    replayChars -= replay.removeFirst().data().length();
                }
                sendOutput(frame);
            }
        }

        private void sendOutput(OutputFrame frame) {
            send(Map.of(
                    "type", "output",
                    "serverSeq", frame.sequence(),
                    "data", frame.data()));
        }

        synchronized void send(Map<String, ?> payload) {
            WebSocketSession current = socket;
            if (current == null || !current.isOpen()) {
                return;
            }
            try {
                current.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
                lastSendAt = System.currentTimeMillis();
            } catch (Exception exception) {
                DIAGNOSTIC.warn("event=ws_send_error sessionId={} socketId={} errorType={} message={}",
                        sessionId, current.getId(), exception.getClass().getSimpleName(),
                        sanitize(exception.getMessage()));
                detach(current);
                closeQuietly(current, CloseStatus.SERVER_ERROR);
            }
        }

        synchronized void shutdown() {
            if (pumpTask != null) {
                pumpTask.cancel(false);
                pumpTask = null;
            }
            WebSocketSession current = socket;
            socket = null;
            if (current != null) {
                closeQuietly(current, CloseStatus.GOING_AWAY);
            }
        }
    }

    private static void closeQuietly(WebSocketSession session, CloseStatus status) {
        if (session == null || !session.isOpen()) {
            return;
        }
        try {
            session.close(status);
        } catch (IOException ignored) {
        }
    }

    private static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "none";
        }
        String sanitized = value.replace('\r', ' ').replace('\n', ' ');
        return sanitized.length() > 160 ? sanitized.substring(0, 160) : sanitized;
    }

    private record SocketBinding(ChannelState state, WebSocketSession socket) {
    }

    private record OutputFrame(long sequence, String data) {
    }

    private static final class TerminalThreadFactory implements ThreadFactory {
        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable,
                    "terminal-websocket-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
