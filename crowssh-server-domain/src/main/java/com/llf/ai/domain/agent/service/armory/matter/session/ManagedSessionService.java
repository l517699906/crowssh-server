package com.llf.ai.domain.agent.service.armory.matter.session;

import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.GetSessionConfig;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.ListEventsResponse;
import com.google.adk.sessions.ListSessionsResponse;
import com.google.adk.sessions.Session;
import com.google.adk.sessions.SessionKey;
import com.google.adk.sessions.State;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

/**
 * 基于 ADK 官方内存实现的会话治理层。
 *
 * <p>本类只负责 CrowSSH 需要的历史净化和容量控制，不重新实现 ADK 的
 * app/user/session 状态语义。
 */
public class ManagedSessionService implements BaseSessionService {

    public static final String ORIGINAL_USER_MESSAGE_STATE_KEY =
            State.TEMP_PREFIX + "crowssh:original-user-message";

    private static final int MAX_TURNS = 4;
    private static final int MAX_EVENTS = 20;
    private static final int MAX_ASSISTANT_TEXT = 2000;
    private static final int MAX_TOOL_TEXT = 1000;

    private final InMemorySessionService delegate = new InMemorySessionService();
    private final Map<SessionKey, PendingOriginalMessage> pendingOriginalMessages = new ConcurrentHashMap<>();
    private final Map<SessionKey, ConcurrentMap<String, String>> committedOriginalMessages =
            new ConcurrentHashMap<>();
    private final Consumer<String> sessionCleanup;

    public ManagedSessionService() {
        this(sessionId -> {
        });
    }

    public ManagedSessionService(Consumer<String> sessionCleanup) {
        this.sessionCleanup = sessionCleanup == null ? sessionId -> {
        } : sessionCleanup;
    }

    @Override
    public Single<Session> createSession(
            String appName,
            String userId,
            ConcurrentMap<String, Object> initialState,
            String sessionId
    ) {
        return delegate.createSession(appName, userId, persistentState(initialState), sessionId);
    }

    @Override
    public Maybe<Session> getSession(
            String appName,
            String userId,
            String sessionId,
            Optional<GetSessionConfig> config
    ) {
        SessionKey key = new SessionKey(appName, userId, sessionId);
        boolean fullHistoryRead = isFullHistoryRead(config);
        return delegate.getSession(appName, userId, sessionId, config)
                .map(session -> applyHistoryGovernance(key, session, fullHistoryRead));
    }

    @Override
    public Single<ListSessionsResponse> listSessions(String appName, String userId) {
        return delegate.listSessions(appName, userId);
    }

    @Override
    public Completable deleteSession(String appName, String userId, String sessionId) {
        SessionKey key = new SessionKey(appName, userId, sessionId);
        return delegate.deleteSession(appName, userId, sessionId)
                .doFinally(() -> clearSessionMetadata(key));
    }

    @Override
    public Single<ListEventsResponse> listEvents(String appName, String userId, String sessionId) {
        SessionKey key = new SessionKey(appName, userId, sessionId);
        return delegate.listEvents(appName, userId, sessionId)
                .map(response -> {
                    Session snapshot = Session.builder(sessionId)
                            .appName(appName)
                            .userId(userId)
                            .state(new ConcurrentHashMap<>())
                            .events(new ArrayList<>(response.events()))
                            .build();
                    applyHistoryGovernance(key, snapshot, false);

                    ListEventsResponse.Builder builder = ListEventsResponse.builder()
                            .events(snapshot.events());
                    response.nextPageToken().ifPresent(builder::nextPageToken);
                    return builder.build();
                });
    }

    @Override
    public Single<Event> appendEvent(Session session, Event event) {
        if (event.partial().orElse(false)) {
            return Single.just(event);
        }

        SessionKey key = session.sessionKey();
        Event persistentEvent = removeTemporaryState(event);
        captureOriginalMessage(key, event);
        return delegate.appendEvent(session, persistentEvent);
    }

    /**
     * 本轮模型和工具执行全部结束后，再让后续历史读取使用原始用户消息。
     */
    public void completeInvocation(String appName, String userId, String sessionId) {
        SessionKey key = new SessionKey(appName, userId, sessionId);
        PendingOriginalMessage pending = pendingOriginalMessages.remove(key);
        if (pending == null) {
            return;
        }
        committedOriginalMessages
                .computeIfAbsent(key, ignored -> new ConcurrentHashMap<>())
                .put(pending.eventId(), pending.message());
    }

    private void captureOriginalMessage(SessionKey key, Event event) {
        if (!isUserEvent(event)) {
            return;
        }
        Object original = event.actions().stateDelta().get(ORIGINAL_USER_MESSAGE_STATE_KEY);
        if (original != null && event.id() != null && !event.id().isBlank()) {
            pendingOriginalMessages.put(
                    key,
                    new PendingOriginalMessage(event.id(), String.valueOf(original))
            );
        }
    }

    private Event removeTemporaryState(Event event) {
        EventActions actions = event.actions();
        Map<String, Object> stateDelta = actions.stateDelta();
        if (stateDelta == null || stateDelta.keySet().stream().noneMatch(this::isTemporaryStateKey)) {
            return event;
        }

        Map<String, Object> persistentDelta = new ConcurrentHashMap<>();
        stateDelta.forEach((key, value) -> {
            if (!isTemporaryStateKey(key)) {
                persistentDelta.put(key, value);
            }
        });
        return event.toBuilder()
                .actions(actions.toBuilder().stateDelta(persistentDelta).build())
                .build();
    }

    private ConcurrentMap<String, Object> persistentState(Map<String, Object> state) {
        ConcurrentMap<String, Object> persistent = new ConcurrentHashMap<>();
        if (state != null) {
            state.forEach((key, value) -> {
                if (!isTemporaryStateKey(key)) {
                    persistent.put(key, value);
                }
            });
        }
        return persistent;
    }

    private boolean isTemporaryStateKey(String key) {
        return key != null && key.startsWith(State.TEMP_PREFIX);
    }

    private boolean isFullHistoryRead(Optional<GetSessionConfig> config) {
        return config.isEmpty()
                || (config.get().numRecentEvents().isEmpty()
                && config.get().afterTimestamp().isEmpty());
    }

    private Session applyHistoryGovernance(
            SessionKey key,
            Session session,
            boolean pruneOriginalMessages
    ) {
        Map<String, String> originalMessages = committedOriginalMessages.getOrDefault(key, new ConcurrentHashMap<>());
        List<Event> governed = new ArrayList<>(session.events().size());
        for (Event event : session.events()) {
            governed.add(governEvent(event, originalMessages.get(event.id())));
        }

        trimToRecentTurns(governed);
        trimToEventBudget(governed);

        session.events().clear();
        session.events().addAll(governed);
        if (pruneOriginalMessages) {
            pruneCommittedMessages(key, governed);
        }
        return session;
    }

    private Event governEvent(Event event, String originalUserMessage) {
        Optional<Content> contentOptional = event.content();
        if (contentOptional.isEmpty()) {
            return event;
        }

        Content content = contentOptional.get();
        List<Part> parts = content.parts().orElse(List.of());
        List<Part> governedParts;
        if (originalUserMessage != null) {
            governedParts = replaceTextParts(parts, originalUserMessage);
        } else {
            int maxTextLength = event.functionResponses().isEmpty()
                    ? MAX_ASSISTANT_TEXT
                    : MAX_TOOL_TEXT;
            governedParts = truncateModelTextParts(event, parts, maxTextLength);
        }

        if (governedParts.equals(parts)) {
            return event;
        }
        return event.toBuilder()
                .content(content.toBuilder().parts(governedParts).build())
                .build();
    }

    private List<Part> replaceTextParts(List<Part> parts, String originalUserMessage) {
        List<Part> result = new ArrayList<>();
        boolean textReplaced = false;
        for (Part part : parts) {
            if (part.text().isPresent()) {
                if (!textReplaced) {
                    result.add(part.toBuilder().text(originalUserMessage).build());
                    textReplaced = true;
                }
                continue;
            }
            result.add(part);
        }
        if (!textReplaced) {
            result.add(0, Part.fromText(originalUserMessage));
        }
        return result;
    }

    private List<Part> truncateModelTextParts(Event event, List<Part> parts, int maxTextLength) {
        if (isUserEvent(event)) {
            return parts;
        }
        List<Part> result = new ArrayList<>(parts.size());
        for (Part part : parts) {
            String text = part.text().orElse(null);
            if (text == null || text.length() <= maxTextLength) {
                result.add(part);
            } else {
                result.add(part.toBuilder()
                        .text(text.substring(0, maxTextLength) + "\n...[历史内容已截断]")
                        .build());
            }
        }
        return result;
    }

    private void trimToRecentTurns(List<Event> events) {
        List<Integer> userEventIndices = new ArrayList<>();
        for (int i = 0; i < events.size(); i++) {
            if (isUserEvent(events.get(i))) {
                userEventIndices.add(i);
            }
        }
        if (userEventIndices.size() <= MAX_TURNS) {
            return;
        }
        int firstKeptIndex = userEventIndices.get(userEventIndices.size() - MAX_TURNS);
        events.subList(0, firstKeptIndex).clear();
    }

    private void trimToEventBudget(List<Event> events) {
        if (events.size() <= MAX_EVENTS) {
            return;
        }

        List<List<Event>> groups = groupToolRoundTrips(events);
        LinkedHashSet<Event> kept = new LinkedHashSet<>();
        int used = 0;
        for (int i = groups.size() - 1; i >= 0; i--) {
            List<Event> group = groups.get(i);
            if (!kept.isEmpty() && used + group.size() > MAX_EVENTS) {
                break;
            }
            kept.addAll(group);
            used += group.size();
        }
        events.removeIf(event -> !kept.contains(event));
    }

    private List<List<Event>> groupToolRoundTrips(List<Event> events) {
        List<List<Event>> groups = new ArrayList<>();
        int index = 0;
        while (index < events.size()) {
            Event event = events.get(index);
            Set<String> callIds = new HashSet<>();
            event.functionCalls().forEach(call -> call.id().ifPresent(callIds::add));
            if (callIds.isEmpty()) {
                groups.add(List.of(event));
                index++;
                continue;
            }

            List<Event> group = new ArrayList<>();
            group.add(event);
            int next = index + 1;
            while (next < events.size() && hasMatchingFunctionResponse(events.get(next), callIds)) {
                group.add(events.get(next));
                next++;
            }
            groups.add(group);
            index = next;
        }
        return groups;
    }

    private boolean hasMatchingFunctionResponse(Event event, Set<String> callIds) {
        return event.functionResponses().stream()
                .anyMatch(response -> response.id().map(callIds::contains).orElse(false));
    }

    private boolean isUserEvent(Event event) {
        if ("user".equalsIgnoreCase(event.author())) {
            return true;
        }
        return event.content()
                .flatMap(Content::role)
                .map("user"::equalsIgnoreCase)
                .orElse(false);
    }

    private void pruneCommittedMessages(SessionKey key, List<Event> retainedEvents) {
        ConcurrentMap<String, String> originals = committedOriginalMessages.get(key);
        if (originals == null) {
            return;
        }
        Set<String> retainedIds = new HashSet<>();
        retainedEvents.stream().map(Event::id).filter(id -> id != null).forEach(retainedIds::add);
        originals.keySet().removeIf(id -> !retainedIds.contains(id));
        if (originals.isEmpty()) {
            committedOriginalMessages.remove(key, originals);
        }
    }

    private void clearSessionMetadata(SessionKey key) {
        pendingOriginalMessages.remove(key);
        committedOriginalMessages.remove(key);
        sessionCleanup.accept(key.id());
    }

    private record PendingOriginalMessage(String eventId, String message) {
    }
}
