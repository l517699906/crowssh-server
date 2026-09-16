package com.llf.ai.api.dto;

import java.util.List;

/** 取消接收状态与执行快照分开返回；RUNNING 不代表已经取消或回滚。 */
public record ChatStreamCancelResponseDTO(String streamState, List<Execution> executions) {
    public record Execution(String executionId, String state, String outcome, boolean cancelRequested) { }
}
