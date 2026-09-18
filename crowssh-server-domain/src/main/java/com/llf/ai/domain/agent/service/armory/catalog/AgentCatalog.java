package com.llf.ai.domain.agent.service.armory.catalog;

import com.google.adk.agents.BaseAgent;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 按业务 Agent 与父节点隔离的不可变派发目录，无跨组兜底。 */
@Component
public class AgentCatalog {
    private record Key(String agentId, String parentName) {}
    private record Group(Map<String, BaseAgent> agents, Set<String> readOnly) {}
    private final Map<Key, Group> groups = new ConcurrentHashMap<>();

    public void register(String agentId, String parentName, Map<String, BaseAgent> agents, Set<String> readOnly) {
        groups.put(new Key(agentId, parentName), new Group(Map.copyOf(agents), Set.copyOf(readOnly)));
    }
    public Optional<BaseAgent> find(String agentId, String parentName, String name) {
        Group group = groups.get(new Key(agentId, parentName));
        return group == null ? Optional.empty() : Optional.ofNullable(group.agents().get(name));
    }
    public boolean isReadOnly(String agentId, String parentName, String name) {
        Group group = groups.get(new Key(agentId, parentName));
        return group != null && group.readOnly().contains(name);
    }
}
