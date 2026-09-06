package com.llf.ai.domain.agent.service.armory.matter.session;

import com.google.adk.memory.BaseMemoryService;
import com.google.adk.memory.SearchMemoryResponse;
import com.google.common.collect.ImmutableList;
import com.google.adk.sessions.Session;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import org.springframework.stereotype.Component;

/**
 * CrowSSH 的 ADK Memory 边界实现。
 *
 * <p>长期记忆由业务侧 {@code ILongTermMemoryService} 管理，ADK 只负责当前
 * Runner 的 Session。因此这里明确不把 Session 再写入 ADK Memory，避免同一份
 * SSH 对话被业务记忆和 ADK 记忆重复保存或重复召回。
 */
@Component
public class CustomAdkMemoryService implements BaseMemoryService {

    @Override
    public Completable addSessionToMemory(Session session) {
        return Completable.complete();
    }

    @Override
    public Single<SearchMemoryResponse> searchMemory(String appName, String userId, String query) {
        return Single.just(SearchMemoryResponse.builder()
                .memories(ImmutableList.of())
                .build());
    }
}
