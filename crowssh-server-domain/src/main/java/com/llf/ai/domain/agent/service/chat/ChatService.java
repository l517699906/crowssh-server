package com.llf.ai.domain.agent.service.chat;

import com.google.adk.events.Event;
import com.google.adk.agents.RunConfig;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.llf.ai.domain.agent.adapter.repository.IChatHistoryRepository;
import com.llf.ai.domain.agent.model.entity.ChatCommandEntity;
import com.llf.ai.domain.agent.model.entity.ChatMessageEntity;
import com.llf.ai.domain.agent.model.entity.ChatSessionEntity;
import com.llf.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import com.llf.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import com.llf.ai.domain.agent.model.valobj.properties.AiAgentAutoConfigProperties;
import com.llf.ai.domain.agent.service.IChatService;
import com.llf.ai.domain.agent.service.IChatContextService;
import com.llf.ai.domain.agent.service.IPromptService;
import com.llf.ai.domain.agent.service.armory.factory.DefaultArmoryFactory;
import com.llf.ai.domain.agent.service.armory.matter.session.ManagedSessionService;
import com.llf.ai.domain.agent.service.armory.matter.tools.SshExecuteAdkTool;
import com.llf.ai.domain.ssh.adapter.port.TerminalSessionEntity;
import com.llf.ai.domain.ssh.service.ISshConnectionService;
import com.llf.ai.domain.ssh.service.ISshTerminalService;
import com.llf.ai.types.enums.ResponseCode;
import com.llf.ai.types.exception.AppException;
import io.reactivex.rxjava3.core.Flowable;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class ChatService implements IChatService {

    @Resource
    private DefaultArmoryFactory defaultArmoryFactory;

    @Resource
    private AiAgentAutoConfigProperties aiAgentAutoConfigProperties;

    @Resource
    private IChatHistoryRepository chatHistoryRepository;

    @Resource
    private ISshTerminalService sshTerminalService;

    @Resource
    private ISshConnectionService sshConnectionService;

    @Resource
    private IChatContextService chatContextService;

    @Resource
    private IPromptService promptService;

    private final Map<String, ChatSessionBinding> sessionBindings = new ConcurrentHashMap<>();

    @Override
    public List<AiAgentConfigTableVO.Agent> queryAiAgentConfigList() {
        Map<String, AiAgentConfigTableVO> tables = aiAgentAutoConfigProperties.getTables();

        List<AiAgentConfigTableVO.Agent> agentList = new ArrayList<>();
        if (null != tables) {
            for (AiAgentConfigTableVO vo : tables.values()) {
                if (null != vo.getModule()) {
                    agentList.add(vo.getAgent());
                }
            }
        }
        return agentList;
    }

    @Override
    public String createSession(String agentId, String userId) {
        return createSession(agentId, userId, null, null);
    }

    @Override
    public String createSession(
            String agentId,
            String userId,
            String connectionId,
            String terminalSessionId
    ) {
        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(agentId);

        if (null == aiAgentRegisterVO) {
            throw new AppException(ResponseCode.E0001.getCode());
        }

        String appName = aiAgentRegisterVO.getAppName();
        Runner runner = aiAgentRegisterVO.getRunner();
        SshResourceContext resourceContext = resolveResourceContext(
                userId, connectionId, terminalSessionId);
        Session session = runner.sessionService()
                .createSession(appName, userId, resourceContext.toState(), null)
                .blockingGet();
        sessionBindings.put(
                session.id(),
                new ChatSessionBinding(agentId, userId, resourceContext.connectionId(),
                        resourceContext.terminalSessionId())
        );
        if (chatHistoryRepository != null) {
            try {
                chatHistoryRepository.saveSession(ChatSessionEntity.builder()
                        .id(session.id())
                        .agentId(agentId)
                        .userId(userId)
                        .connectionId(resourceContext.connectionId())
                        .terminalSessionId(resourceContext.terminalSessionId())
                        .title("新会话")
                        .messageCount(0)
                        .build());
            } catch (RuntimeException e) {
                // 历史表是旁路能力，数据库不可用时不阻断 ADK 会话创建。
                log.warn("保存会话元数据失败 sessionId={}", session.id(), e);
            }
        }
        return session.id();
    }

    @Override
    public String resolveSession(
            String agentId,
            String userId,
            String requestedSessionId,
            String connectionId,
            String terminalSessionId
    ) {
        String normalizedSessionId = normalize(requestedSessionId);
        SshResourceContext resourceContext = resolveResourceContext(
                userId, connectionId, terminalSessionId);

        if (normalizedSessionId == null) {
            return createSession(agentId, userId, resourceContext.connectionId(), resourceContext.terminalSessionId());
        }

        AiAgentRegisterVO registerVO = defaultArmoryFactory.getAiAgentRegisterVO(agentId);
        if (registerVO == null) {
            throw new AppException(ResponseCode.E0001.getCode());
        }

        ChatSessionBinding binding = sessionBindings.get(normalizedSessionId);
        if (binding != null) {
            // 已存在的绑定仍必须通过归属校验，不能把错误的用户/智能体/服务器 ID 当成“旧会话”恢复。
            try {
                validateSessionBinding(normalizedSessionId, agentId, userId,
                        resourceContext.connectionId(), resourceContext.terminalSessionId());
            } catch (IllegalArgumentException error) {
                // 终端重连会让旧终端失效；此时旧 AI 会话不能复用，但可以安全地创建新会话。
                if (!isStaleTerminalBinding(userId, binding.terminalSessionId(), resourceContext.terminalSessionId())) {
                    throw error;
                }
                log.info("AI 会话绑定的旧终端已失效，创建新会话 oldSessionId={} oldTerminalSessionId={} newTerminalSessionId={}",
                        normalizedSessionId, binding.terminalSessionId(), resourceContext.terminalSessionId());
                invalidateSession(registerVO, userId, normalizedSessionId, binding);
                return createSession(agentId, userId, resourceContext.connectionId(), resourceContext.terminalSessionId());
            }
            if (adkSessionExists(registerVO.getRunner(), registerVO.getAppName(), userId, normalizedSessionId)) {
                return normalizedSessionId;
            }
            if (restorePersistedSession(registerVO, agentId, userId, normalizedSessionId, resourceContext)) {
                return normalizedSessionId;
            }
        } else if (restorePersistedSession(registerVO, agentId, userId, normalizedSessionId, resourceContext)) {
            // ChatService 或 Runner 重建后，内存绑定和 ADK 内存会话都可能丢失。
            // 只有通过数据库归属查询后，才允许使用客户端带回的原 sessionId。
            return normalizedSessionId;
        }

        log.info("AI 会话已失效，创建新会话 agentId={} userId={} oldSessionId={}",
                agentId, userId, normalizedSessionId);
        invalidateSession(registerVO, userId, normalizedSessionId, binding);
        return createSession(agentId, userId, resourceContext.connectionId(), resourceContext.terminalSessionId());
    }

    @Override
    public List<String> handleMessage(String agentId, String userId, String message) {

        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(agentId);

        if (null == aiAgentRegisterVO) {
            throw new AppException(ResponseCode.E0001.getCode());
        }

        String session = createSession(agentId, userId);

        return handleMessage(agentId, userId, session, message);
    }

    @Override
    public List<String> handleMessage(String agentId, String userId, String sessionId, String message) {

        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(agentId);

        if (null == aiAgentRegisterVO) {
            throw new AppException(ResponseCode.E0001.getCode());
        }

        String resolvedSessionId = resolveSession(agentId, userId, sessionId, null, null);

        Content userMsg = Content.fromParts(Part.fromText(message));
        Runner runner = aiAgentRegisterVO.getRunner();
        Flowable<Event> events = withSessionGovernance(
                runner.runAsync(userId, resolvedSessionId, userMsg),
                runner,
                aiAgentRegisterVO.getAppName(),
                userId,
                resolvedSessionId
        );

        List<String> outputs = new ArrayList<>();
        events.blockingForEach(event -> outputs.add(event.stringifyContent()));

        return outputs;
    }

    @Override
    public Flowable<Event> handleMessageStream(String agentId, String userId, String sessionId, String message) {
        return handleMessageStream(agentId, userId, sessionId, message, null, null);
    }

    @Override
    public Flowable<Event> handleMessageStream(String agentId, String userId, String sessionId, String message, String terminalSessionId) {
        return handleMessageStream(agentId, userId, sessionId, message, terminalSessionId, null);
    }

    @Override
    public Flowable<Event> handleMessageStream(
            String agentId,
            String userId,
            String sessionId,
            String message,
            String terminalSessionId,
            String connectionId
    ) {
        return handleMessageStreamInternal(
                agentId, userId, sessionId, message, null, terminalSessionId, connectionId);
    }

    @Override
    public Flowable<Event> handleEnrichedMessageStream(
            String agentId,
            String userId,
            String sessionId,
            String enrichedMessage,
            String originalMessage,
            String terminalSessionId,
            String connectionId
    ) {
        return handleMessageStreamInternal(
                agentId,
                userId,
                sessionId,
                enrichedMessage,
                originalMessage,
                terminalSessionId,
                connectionId
        );
    }

    private Flowable<Event> handleMessageStreamInternal(
            String agentId,
            String userId,
            String sessionId,
            String message,
            String originalMessage,
            String terminalSessionId,
            String connectionId
    ) {
        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(agentId);

        if (null == aiAgentRegisterVO) {
            throw new AppException(ResponseCode.E0001.getCode());
        }

        String resolvedSessionId = resolveSession(
                agentId,
                userId,
                sessionId,
                connectionId,
                terminalSessionId
        );
        SshResourceContext resourceContext = resolveResourceContext(
                userId, connectionId, terminalSessionId);

        Map<String, Object> stateDelta = resourceContext.toState();
        if (originalMessage != null) {
            stateDelta.put(ManagedSessionService.ORIGINAL_USER_MESSAGE_STATE_KEY, originalMessage);
        }

        Content userMsg = Content.fromParts(Part.fromText(message));
        Runner runner = aiAgentRegisterVO.getRunner();
        Flowable<Event> events = runner.runAsync(
                userId,
                resolvedSessionId,
                userMsg,
                RunConfig.builder().build(),
                stateDelta
        );
        return withSessionGovernance(
                events, runner, aiAgentRegisterVO.getAppName(), userId, resolvedSessionId);
    }

    private SshResourceContext resolveResourceContext(
            String ownerId, String connectionId, String terminalSessionId) {
        if (ownerId == null || ownerId.isBlank()) {
            throw new IllegalArgumentException("设备身份不能为空");
        }
        String requestedConnectionId = normalize(connectionId);
        String requestedTerminalSessionId = normalize(terminalSessionId);
        if (requestedTerminalSessionId == null) {
            if (requestedConnectionId != null) {
                sshConnectionService.requireOwnership(ownerId, requestedConnectionId);
            }
            return new SshResourceContext(ownerId, requestedConnectionId, null);
        }

        TerminalSessionEntity terminalSession = sshTerminalService.getTerminalSession(
                ownerId, requestedTerminalSessionId);
        if (terminalSession == null || !terminalSession.isActive()) {
            throw new IllegalArgumentException("SSH 终端会话不存在或已关闭");
        }

        String actualConnectionId = normalize(terminalSession.getConnectionId());
        if (requestedConnectionId != null && !Objects.equals(requestedConnectionId, actualConnectionId)) {
            throw new IllegalArgumentException("AI 对话绑定的服务器与当前 SSH 终端不一致");
        }
        return new SshResourceContext(ownerId, actualConnectionId, requestedTerminalSessionId);
    }

    private void validateSessionBinding(
            String sessionId,
            String agentId,
            String userId,
            String connectionId,
            String terminalSessionId
    ) {
        // 绑定补齐使用 CAS；如果并发请求先一步绑定了其他资源，必须重新读取并重新校验，
        // 不能在 CAS 失败后继续沿用本次请求的资源上下文。
        for (;;) {
            ChatSessionBinding current = sessionBindings.get(sessionId);
            if (current == null) {
                throw new IllegalArgumentException("AI 会话不存在或已失效，请新建对话");
            }
            if (!Objects.equals(current.agentId(), agentId) || !Objects.equals(current.userId(), userId)) {
                throw new IllegalArgumentException("AI 会话不属于当前用户或智能体");
            }
            if (current.connectionId() != null && connectionId != null
                    && !Objects.equals(current.connectionId(), connectionId)) {
                throw new IllegalArgumentException("AI 会话不能切换到其他 SSH 服务器");
            }
            if (current.terminalSessionId() != null && terminalSessionId != null
                    && !Objects.equals(current.terminalSessionId(), terminalSessionId)) {
                throw new IllegalArgumentException("AI 会话不能切换到其他 SSH 终端");
            }

            // 请求没有携带资源时沿用会话原绑定；携带新资源时只允许补齐空字段，不能切换资源。
            String effectiveConnectionId = current.connectionId() != null
                    ? current.connectionId() : connectionId;
            String effectiveTerminalSessionId = current.terminalSessionId() != null
                    ? current.terminalSessionId() : terminalSessionId;
            ChatSessionBinding updated = new ChatSessionBinding(
                    agentId, userId, effectiveConnectionId, effectiveTerminalSessionId);
            if (Objects.equals(current, updated)) {
                return;
            }
            if (sessionBindings.replace(sessionId, current, updated)) {
                persistSessionBinding(sessionId, updated);
                return;
            }
        }
    }

    private boolean isStaleTerminalBinding(
            String ownerId,
            String oldTerminalSessionId,
            String newTerminalSessionId
    ) {
        String oldId = normalize(oldTerminalSessionId);
        String newId = normalize(newTerminalSessionId);
        if (oldId == null || newId == null || Objects.equals(oldId, newId)) {
            return false;
        }
        TerminalSessionEntity oldTerminal = sshTerminalService.getTerminalSession(ownerId, oldId);
        return oldTerminal == null || !oldTerminal.isActive();
    }

    private boolean adkSessionExists(Runner runner, String appName, String userId, String sessionId) {
        try {
            Session session = runner.sessionService()
                    .getSession(appName, userId, sessionId, Optional.empty())
                    .blockingGet();
            return session != null;
        } catch (RuntimeException e) {
            log.debug("检查 ADK 会话失败 sessionId={} reason={}", sessionId, e.getMessage());
            return false;
        }
    }

    @Override
    public ChatSessionEntity getSessionContext(String agentId, String userId, String sessionId) {
        String normalizedSessionId = normalize(sessionId);
        if (normalizedSessionId == null || userId == null || userId.isBlank()) {
            return null;
        }
        ChatSessionBinding binding = sessionBindings.get(normalizedSessionId);
        if (binding != null
                && Objects.equals(binding.agentId(), agentId)
                && Objects.equals(binding.userId(), userId)) {
            return ChatSessionEntity.builder()
                    .id(normalizedSessionId)
                    .agentId(binding.agentId())
                    .userId(binding.userId())
                    .connectionId(binding.connectionId())
                    .terminalSessionId(binding.terminalSessionId())
                    .build();
        }
        if (chatHistoryRepository == null) {
            return null;
        }
        try {
            return chatHistoryRepository.findSession(agentId, userId, normalizedSessionId);
        } catch (RuntimeException e) {
            log.debug("查询会话资源绑定失败 sessionId={} reason={}", normalizedSessionId, e.getMessage());
            return null;
        }
    }

    /**
     * 按数据库会话元数据恢复内存中的 ADK Session。
     * <p>
     * ADK 当前使用的是进程内 SessionService，服务重启后只能重建空 Session；
     * RootNode 随后会从 chat_message 加载历史，因此这里必须保留原 sessionId。
     */
    private boolean restorePersistedSession(
            AiAgentRegisterVO registerVO,
            String agentId,
            String userId,
            String sessionId,
            SshResourceContext resourceContext
    ) {
        if (chatHistoryRepository == null) {
            return false;
        }

        ChatSessionEntity persisted;
        try {
            persisted = chatHistoryRepository.findSession(agentId, userId, sessionId);
        } catch (RuntimeException e) {
            log.warn("查询持久化会话归属失败 sessionId={}", sessionId, e);
            return false;
        }
        if (persisted == null) {
            return false;
        }

        String persistedConnectionId = normalize(persisted.getConnectionId());
        String persistedTerminalSessionId = normalize(persisted.getTerminalSessionId());
        if (resourceContext.connectionId() != null && persistedConnectionId != null
                && !Objects.equals(resourceContext.connectionId(), persistedConnectionId)) {
            throw new IllegalArgumentException("AI 会话不能切换到其他 SSH 服务器");
        }
        if (resourceContext.terminalSessionId() != null && persistedTerminalSessionId != null
                && !Objects.equals(resourceContext.terminalSessionId(), persistedTerminalSessionId)) {
            if (isStaleTerminalBinding(userId, persistedTerminalSessionId, resourceContext.terminalSessionId())) {
                log.info("持久化 AI 会话绑定的旧终端已失效，允许创建新会话 sessionId={} oldTerminalSessionId={} newTerminalSessionId={}",
                        sessionId, persistedTerminalSessionId, resourceContext.terminalSessionId());
                return false;
            }
            throw new IllegalArgumentException("AI 会话不能切换到其他 SSH 终端");
        }

        String bindingConnectionId = resourceContext.connectionId() != null
                ? resourceContext.connectionId() : persistedConnectionId;
        String bindingTerminalSessionId = resourceContext.terminalSessionId() != null
                ? resourceContext.terminalSessionId() : persistedTerminalSessionId;
        if (bindingTerminalSessionId != null) {
            TerminalSessionEntity persistedTerminal = sshTerminalService.getTerminalSession(
                    userId, bindingTerminalSessionId);
            if (persistedTerminal == null || !persistedTerminal.isActive()) {
                if (resourceContext.terminalSessionId() != null) {
                    throw new IllegalArgumentException("SSH 终端会话不存在或已关闭");
                }
                bindingTerminalSessionId = null;
                bindingConnectionId = null;
            } else {
                String terminalConnectionId = normalize(persistedTerminal.getConnectionId());
                if (bindingConnectionId != null && terminalConnectionId != null
                        && !Objects.equals(bindingConnectionId, terminalConnectionId)) {
                    throw new IllegalArgumentException("持久化会话绑定的服务器与 SSH 终端不一致");
                }
                bindingConnectionId = terminalConnectionId;
            }
        }
        if (bindingTerminalSessionId == null && bindingConnectionId != null) {
            sshConnectionService.requireOwnership(userId, bindingConnectionId);
        }
        SshResourceContext restoredResourceContext = new SshResourceContext(
                userId, bindingConnectionId, bindingTerminalSessionId);
        ChatSessionBinding restoredBinding = new ChatSessionBinding(
                agentId,
                userId,
                restoredResourceContext.connectionId(),
                restoredResourceContext.terminalSessionId());
        boolean bindingChanged = !Objects.equals(persistedConnectionId, restoredBinding.connectionId())
                || !Objects.equals(persistedTerminalSessionId, restoredBinding.terminalSessionId());

        Runner runner = registerVO.getRunner();
        if (adkSessionExists(runner, registerVO.getAppName(), userId, sessionId)) {
            installRestoredBinding(sessionId, restoredBinding);
            if (bindingChanged) {
                persistSessionBinding(sessionId, restoredBinding);
            }
            return true;
        }

        try {
            Session restored = runner.sessionService()
                    .createSession(registerVO.getAppName(), userId, restoredResourceContext.toState(), sessionId)
                    .blockingGet();
            if (restored == null) {
                return false;
            }
            installRestoredBinding(sessionId, restoredBinding);
            if (bindingChanged) {
                persistSessionBinding(sessionId, restoredBinding);
            }
            log.info("从持久化元数据恢复 ADK 会话 agentId={} userId={} sessionId={}",
                    agentId, userId, sessionId);
            return true;
        } catch (RuntimeException e) {
            // 并发请求可能已经用同一 ID 创建成功；再次读取确认即可复用。
            if (adkSessionExists(runner, registerVO.getAppName(), userId, sessionId)) {
                installRestoredBinding(sessionId, restoredBinding);
                if (bindingChanged) {
                    persistSessionBinding(sessionId, restoredBinding);
                }
                return true;
            }
            log.warn("重建 ADK 会话失败 sessionId={}", sessionId, e);
            return false;
        }
    }

    /**
     * 安装从数据库恢复的会话绑定。
     * <p>
     * 恢复路径可能在同一 ChatService 实例中运行（例如 Runner 被重新装配），
     * 此时旧的内存绑定不一定和数据库一致；不能使用 {@code putIfAbsent} 留下过期资源。
     */
    private void installRestoredBinding(String sessionId, ChatSessionBinding restoredBinding) {
        ChatSessionBinding previous = sessionBindings.put(sessionId, restoredBinding);
        if (previous != null && !Objects.equals(previous, restoredBinding)) {
            log.info("已用持久化会话绑定覆盖旧内存绑定 sessionId={}", sessionId);
        }
    }

    private void persistSessionBinding(String sessionId, ChatSessionBinding binding) {
        if (chatHistoryRepository == null || binding == null) {
            return;
        }
        try {
            int updatedRows = chatHistoryRepository.updateSessionBinding(
                    binding.agentId(),
                    binding.userId(),
                    sessionId,
                    binding.connectionId(),
                    binding.terminalSessionId());
            if (updatedRows > 0) {
                return;
            }

            // 会话创建时数据库可能短暂不可用，首次绑定时 UPDATE 可能匹配不到记录。
            // 先按 agent + owner 复查，只有确认元数据缺失才尝试插入，避免覆盖其他主体的同名会话。
            if (chatHistoryRepository.findSession(binding.agentId(), binding.userId(), sessionId) != null) {
                return;
            }
            chatHistoryRepository.saveSession(ChatSessionEntity.builder()
                    .id(sessionId)
                    .agentId(binding.agentId())
                    .userId(binding.userId())
                    .connectionId(binding.connectionId())
                    .terminalSessionId(binding.terminalSessionId())
                    .title("恢复会话")
                    .messageCount(0)
                    .build());
        } catch (RuntimeException e) {
            // 会话绑定持久化是旁路能力，不阻断当前已恢复的 ADK 会话。
            log.warn("更新会话资源绑定失败 sessionId={}", sessionId, e);
        }
    }

    private void invalidateSession(
            AiAgentRegisterVO registerVO,
            String userId,
            String sessionId,
            ChatSessionBinding binding
    ) {
        if (binding != null) {
            sessionBindings.remove(sessionId, binding);
        }
        try {
            registerVO.getRunner().sessionService()
                    .deleteSession(registerVO.getAppName(), userId, sessionId)
                    .blockingAwait();
        } catch (RuntimeException e) {
            log.debug("删除失效 ADK 会话失败 sessionId={} reason={}", sessionId, e.getMessage());
        }

        if (!(registerVO.getRunner().sessionService() instanceof ManagedSessionService)) {
            clearSessionContext(sessionId);
        }
    }

    private Flowable<Event> withSessionGovernance(
            Flowable<Event> events,
            Runner runner,
            String appName,
            String userId,
            String sessionId
    ) {
        if (!(runner.sessionService() instanceof ManagedSessionService managedSessionService)) {
            return events;
        }
        return events.doFinally(() ->
                managedSessionService.completeInvocation(appName, userId, sessionId));
    }

    private void clearSessionContext(String sessionId) {
        if (chatContextService != null) {
            chatContextService.clearSessionContext(sessionId);
        }
        if (promptService != null) {
            promptService.clearMilestones(sessionId);
        }
    }

    @Override
    public List<ChatSessionEntity> querySessionList(String agentId, String userId, int limit) {
        return chatHistoryRepository.querySessionList(agentId, userId, limit > 0 ? limit : 20);
    }

    @Override
    public List<ChatMessageEntity> queryMessageList(String userId, String sessionId, int limit) {
        return chatHistoryRepository.queryMessageList(
                userId, sessionId, limit > 0 ? limit : 100);
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private record ChatSessionBinding(
            String agentId,
            String userId,
            String connectionId,
            String terminalSessionId
    ) {
    }

    private record SshResourceContext(String ownerId, String connectionId, String terminalSessionId) {
        private Map<String, Object> toState() {
            Map<String, Object> state = new HashMap<>();
            state.put(SshExecuteAdkTool.OWNER_ID_STATE_KEY, ownerId);
            state.put(SshExecuteAdkTool.CONNECTION_ID_STATE_KEY, connectionId == null ? "" : connectionId);
            state.put(
                    SshExecuteAdkTool.TERMINAL_SESSION_ID_STATE_KEY,
                    terminalSessionId == null ? "" : terminalSessionId
            );
            return state;
        }
    }

    @Override
    public List<String> handleMessage(ChatCommandEntity chatCommandEntity) {

        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(chatCommandEntity.getAgentId());

        if (null == aiAgentRegisterVO) {
            throw new AppException(ResponseCode.E0001.getCode());
        }

        List<Part> parts = new ArrayList<>();

        List<ChatCommandEntity.Content.Text> texts = chatCommandEntity.getTexts();
        if (null != texts && !texts.isEmpty()) {
            for (ChatCommandEntity.Content.Text text : texts) {
                parts.add(Part.fromText(text.getMessage()));
            }
        }

        List<ChatCommandEntity.Content.File> files = chatCommandEntity.getFiles();
        if (null != files && !files.isEmpty()) {
            for (ChatCommandEntity.Content.File file : files) {
                parts.add(Part.fromUri(file.getFileUri(), file.getMimeType()));
            }
        }

        List<ChatCommandEntity.Content.InlineData> inlineDatas = chatCommandEntity.getInlineDatas();
        if (null != inlineDatas && !inlineDatas.isEmpty()) {
            for (ChatCommandEntity.Content.InlineData inlineData : inlineDatas) {
                parts.add(Part.fromBytes(inlineData.getBytes(), inlineData.getMimeType()));
            }
        }

        Content content = Content.builder().role("user").parts(parts).build();

        String resolvedSessionId = resolveSession(
                chatCommandEntity.getAgentId(),
                chatCommandEntity.getUserId(),
                chatCommandEntity.getSessionId(),
                null,
                null
        );

        Runner runner = aiAgentRegisterVO.getRunner();
        Flowable<Event> events = withSessionGovernance(runner.runAsync(
                chatCommandEntity.getUserId(),
                resolvedSessionId,
                content
        ), runner, aiAgentRegisterVO.getAppName(), chatCommandEntity.getUserId(), resolvedSessionId);

        List<String> outputs = new ArrayList<>();
        events.blockingForEach(event -> outputs.add(event.stringifyContent()));

        return outputs;
    }
}
