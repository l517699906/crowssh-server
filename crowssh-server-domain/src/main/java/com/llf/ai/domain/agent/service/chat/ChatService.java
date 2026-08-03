package com.llf.ai.domain.agent.service.chat;

import com.google.adk.events.Event;
import com.google.adk.agents.RunConfig;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.llf.ai.domain.agent.model.entity.ChatCommandEntity;
import com.llf.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import com.llf.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import com.llf.ai.domain.agent.model.valobj.properties.AiAgentAutoConfigProperties;
import com.llf.ai.domain.agent.service.IChatService;
import com.llf.ai.domain.agent.service.armory.factory.DefaultArmoryFactory;
import com.llf.ai.domain.agent.service.armory.matter.tools.SshExecuteAdkTool;
import com.llf.ai.domain.ssh.adapter.port.TerminalSessionEntity;
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
    private ISshTerminalService sshTerminalService;

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
        InMemoryRunner runner = aiAgentRegisterVO.getRunner();
        SshResourceContext resourceContext = resolveResourceContext(connectionId, terminalSessionId);
        Session session = runner.sessionService()
                .createSession(appName, userId, resourceContext.toState(), null)
                .blockingGet();
        sessionBindings.put(
                session.id(),
                new ChatSessionBinding(agentId, userId, resourceContext.connectionId())
        );
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
        SshResourceContext resourceContext = resolveResourceContext(connectionId, terminalSessionId);

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
            validateSessionBinding(normalizedSessionId, agentId, userId, resourceContext.connectionId());
            if (adkSessionExists(registerVO.getRunner(), registerVO.getAppName(), userId, normalizedSessionId)) {
                return normalizedSessionId;
            }
        }

        log.info("AI 会话已失效，创建新会话 agentId={} userId={} oldSessionId={}",
                agentId, userId, normalizedSessionId);
        if (binding != null) {
            sessionBindings.remove(normalizedSessionId, binding);
        }
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
        Flowable<Event> events = aiAgentRegisterVO.getRunner().runAsync(userId, resolvedSessionId, userMsg);

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
        SshResourceContext resourceContext = resolveResourceContext(connectionId, terminalSessionId);

        Content userMsg = Content.fromParts(Part.fromText(message));
        return aiAgentRegisterVO.getRunner().runAsync(
                userId,
                resolvedSessionId,
                userMsg,
                RunConfig.builder().build(),
                resourceContext.toState()
        );
    }

    private SshResourceContext resolveResourceContext(String connectionId, String terminalSessionId) {
        String requestedConnectionId = normalize(connectionId);
        String requestedTerminalSessionId = normalize(terminalSessionId);
        if (requestedTerminalSessionId == null) {
            return new SshResourceContext(requestedConnectionId, null);
        }

        TerminalSessionEntity terminalSession = sshTerminalService.getTerminalSession(requestedTerminalSessionId);
        if (terminalSession == null || !terminalSession.isActive()) {
            throw new IllegalArgumentException("SSH 终端会话不存在或已关闭");
        }

        String actualConnectionId = normalize(terminalSession.getConnectionId());
        if (requestedConnectionId != null && !Objects.equals(requestedConnectionId, actualConnectionId)) {
            throw new IllegalArgumentException("AI 对话绑定的服务器与当前 SSH 终端不一致");
        }
        return new SshResourceContext(actualConnectionId, requestedTerminalSessionId);
    }

    private void validateSessionBinding(
            String sessionId,
            String agentId,
            String userId,
            String connectionId
    ) {
        ChatSessionBinding current = sessionBindings.get(sessionId);
        if (current == null) {
            throw new IllegalArgumentException("AI 会话不存在或已失效，请新建对话");
        }
        if (!Objects.equals(current.agentId(), agentId) || !Objects.equals(current.userId(), userId)) {
            throw new IllegalArgumentException("AI 会话不属于当前用户或智能体");
        }
        if (current.connectionId() != null
                && connectionId != null
                && !Objects.equals(current.connectionId(), connectionId)) {
            throw new IllegalArgumentException("AI 会话不能切换到其他 SSH 服务器");
        }
        if (current.connectionId() == null && connectionId != null) {
            sessionBindings.replace(
                    sessionId,
                    current,
                    new ChatSessionBinding(agentId, userId, connectionId)
            );
        }
    }

    private boolean adkSessionExists(InMemoryRunner runner, String appName, String userId, String sessionId) {
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

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private record ChatSessionBinding(String agentId, String userId, String connectionId) {
    }

    private record SshResourceContext(String connectionId, String terminalSessionId) {
        private Map<String, Object> toState() {
            Map<String, Object> state = new HashMap<>();
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

        Flowable<Event> events = aiAgentRegisterVO.getRunner().runAsync(
                chatCommandEntity.getUserId(),
                resolvedSessionId,
                content
        );

        List<String> outputs = new ArrayList<>();
        events.blockingForEach(event -> outputs.add(event.stringifyContent()));

        return outputs;
    }
}
