package com.llf.ai.trigger.http.db;

import com.llf.ai.api.dto.DbCancelRequestDTO;
import com.llf.ai.api.dto.DbCancelResponseDTO;
import com.llf.ai.api.dto.DbExecuteRequestDTO;
import com.llf.ai.api.dto.DbExecutionStatusDTO;
import com.llf.ai.api.dto.DbPrepareRequestDTO;
import com.llf.ai.api.dto.DbPrepareResponseDTO;
import com.llf.ai.api.dto.DbSelectDatabaseRequestDTO;
import com.llf.ai.api.dto.DbSessionOpenRequestDTO;
import com.llf.ai.api.dto.DbSessionOpenResponseDTO;
import com.llf.ai.api.response.Response;
import com.llf.ai.domain.db.adapter.port.DbSessionBusyException;
import com.llf.ai.domain.db.model.entity.DbExecutionEntity;
import com.llf.ai.domain.db.model.entity.DbQueryResultEntity;
import com.llf.ai.domain.db.model.entity.DbSessionEntity;
import com.llf.ai.domain.db.model.valobj.DbExecutionSourceEnum;
import com.llf.ai.domain.db.service.IDbSessionService;
import com.llf.ai.types.enums.ResponseCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;

@Slf4j
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "crowssh.db.enabled", havingValue = "true")
@RestController
@RequestMapping("/api/v1/db/session")
public class DbSessionController {
    private final IDbSessionService sessionService;

    public DbSessionController(IDbSessionService sessionService) {
        this.sessionService = sessionService;
    }

    @PostMapping("open")
    public Response<DbSessionOpenResponseDTO> open(@RequestBody DbSessionOpenRequestDTO request,
                                                   Principal principal) {
        try {
            return success(toSession(sessionService.open(principal.getName(), request.getConnectionId())));
        } catch (com.llf.ai.domain.db.adapter.port.DbTargetBlockedException e) {
            return failure(ResponseCode.DB_TARGET_BLOCKED, e.getMessage());
        } catch (com.llf.ai.domain.db.adapter.port.DbTlsVerificationException e) {
            return failure(ResponseCode.DB_TLS_VERIFICATION_FAILED, e.getMessage());
        } catch (com.llf.ai.domain.db.adapter.port.DbSessionQuotaException e) {
            return failure(ResponseCode.DB_SESSION_QUOTA_EXCEEDED, e.getMessage());
        } catch (IllegalStateException e) {
            return failure(ResponseCode.DB_CONNECTION_FAILED, e.getMessage());
        } catch (IllegalArgumentException e) {
            return failure(ResponseCode.DB_SESSION_NOT_FOUND, e.getMessage());
        }
    }

    @PostMapping("prepare")
    public Response<DbPrepareResponseDTO> prepare(@RequestBody DbPrepareRequestDTO request,
                                                  Principal principal) {
        try {
            DbExecutionEntity execution = sessionService.prepare(principal.getName(), request.getDbSessionId(),
                    request.getSql(), request.getExpectedTargetContextVersion() == null
                            ? 0 : request.getExpectedTargetContextVersion(), DbExecutionSourceEnum.CONSOLE);
            return success(toPrepare(execution));
        } catch (DbSessionBusyException e) {
            return failure(ResponseCode.DB_SESSION_BUSY, e.getMessage());
        } catch (IllegalStateException e) {
            return failure(ResponseCode.DB_CONTEXT_CHANGED, e.getMessage());
        } catch (IllegalArgumentException e) {
            return failure(ResponseCode.DB_STATEMENT_REJECTED, e.getMessage());
        }
    }

    @PostMapping("execute")
    public Response<DbExecutionStatusDTO> execute(@RequestBody DbExecuteRequestDTO request,
                                                  Principal principal) {
        try {
            return success(toStatus(sessionService.executeAsync(principal.getName(), request.getDbSessionId(),
                    request.getExecutionId())));
        } catch (DbSessionBusyException e) {
            return failure(ResponseCode.DB_SESSION_BUSY, e.getMessage());
        } catch (IllegalArgumentException e) {
            return failure(ResponseCode.DB_EXECUTION_NOT_FOUND, e.getMessage());
        } catch (IllegalStateException e) {
            return failure(ResponseCode.DB_SESSION_BROKEN, e.getMessage());
        }
    }

    @PostMapping("cancel")
    public Response<DbCancelResponseDTO> cancel(@RequestBody DbCancelRequestDTO request,
                                                Principal principal) {
        try {
            DbExecutionEntity execution = sessionService.cancel(principal.getName(), request.getDbSessionId(),
                    request.getExecutionId());
            return success(DbCancelResponseDTO.builder().executionId(execution.getExecutionId())
                    .cancelRequested(execution.isCancelRequested()).state(execution.getState().name())
                    .outcome(execution.getOutcome() == null ? null : execution.getOutcome().name()).build());
        } catch (IllegalArgumentException e) {
            return failure(ResponseCode.DB_EXECUTION_NOT_FOUND, e.getMessage());
        }
    }

    @GetMapping("execution")
    public Response<DbExecutionStatusDTO> execution(@RequestParam String dbSessionId,
                                                    @RequestParam String executionId,
                                                    Principal principal) {
        try {
            return success(toStatus(sessionService.getExecution(principal.getName(), dbSessionId, executionId)));
        } catch (IllegalArgumentException e) {
            return failure(ResponseCode.DB_EXECUTION_NOT_FOUND, e.getMessage());
        }
    }

    @PostMapping("select_database")
    public Response<DbSessionOpenResponseDTO> selectDatabase(@RequestBody DbSelectDatabaseRequestDTO request,
                                                              Principal principal) {
        try {
            return success(toSession(sessionService.selectDatabase(principal.getName(), request.getDbSessionId(),
                    request.getDatabase(), request.getExpectedTargetContextVersion() == null
                            ? 0 : request.getExpectedTargetContextVersion())));
        } catch (DbSessionBusyException e) {
            return failure(ResponseCode.DB_SESSION_BUSY, e.getMessage());
        } catch (IllegalStateException e) {
            return failure(ResponseCode.DB_CONTEXT_CHANGED, e.getMessage());
        } catch (IllegalArgumentException e) {
            return failure(ResponseCode.ILLEGAL_PARAMETER, e.getMessage());
        }
    }

    @PostMapping("close")
    public Response<com.llf.ai.api.dto.DbSessionCloseResponseDTO> close(@RequestBody DbSessionOpenRequestDTO request, Principal principal) {
        try {
            sessionService.close(principal.getName(), request.getDbSessionId());
            String status = sessionService.list(principal.getName()).stream()
                    .filter(session -> request.getDbSessionId().equals(session.getDbSessionId()))
                    .map(session -> session.getLifecycleStatus().name()).findFirst().orElse("CLOSED");
            return success(new com.llf.ai.api.dto.DbSessionCloseResponseDTO(request.getDbSessionId(), status));
        } catch (IllegalArgumentException e) {
            return failure(ResponseCode.DB_SESSION_NOT_FOUND, e.getMessage());
        }
    }

    @GetMapping("list")
    public Response<List<DbSessionOpenResponseDTO>> list(Principal principal) {
        return success(sessionService.list(principal.getName()).stream().map(this::toSession).toList());
    }

    private DbPrepareResponseDTO toPrepare(DbExecutionEntity e) {
        return DbPrepareResponseDTO.builder().executionId(e.getExecutionId()).state(e.getState().name())
                .outcome(e.getOutcome() == null ? null : e.getOutcome().name())
                .kind(e.getStatementKind().name()).riskLevel(e.getRiskLevel())
                .reasons(e.getPolicyReason() == null ? List.of() : List.of(e.getPolicyReason()))
                .sqlHash(e.getSqlHash()).targetDatabase(e.getTargetDatabase())
                .configVersion(e.getConfigVersion()).targetContextVersion(e.getTargetContextVersion())
                .expiresAt(e.getExpiresAt()).build();
    }

    private DbExecutionStatusDTO toStatus(DbExecutionEntity e) {
        boolean success = e.getOutcome() == com.llf.ai.domain.db.model.valobj.DbExecutionOutcome.SUCCEEDED;
        return DbExecutionStatusDTO.builder().executionId(e.getExecutionId())
                .source(e.getSource().name()).operationKind(e.getOperationKind().name())
                .kind(e.getStatementKind().name()).state(e.getState().name())
                .outcome(e.getOutcome() == null ? null : e.getOutcome().name()).success(success)
                .cancelRequested(e.isCancelRequested()).resultAvailable(e.isResultAvailable())
                .targetDatabase(e.getTargetDatabase()).configVersion(e.getConfigVersion())
                .riskLevel(e.getRiskLevel()).policyReason(e.getPolicyReason())
                .startedAt(e.getStartedAt() == null ? null : e.getStartedAt().toString())
                .finishedAt(e.getFinishedAt() == null ? null : e.getFinishedAt().toString())
                .result(toResult(e.getResult())).build();
    }

    private com.llf.ai.api.dto.DbQueryResultDTO toResult(DbQueryResultEntity result) {
        if (result == null) return null;
        return com.llf.ai.api.dto.DbQueryResultDTO.builder()
                .columns(result.getColumns().stream().map(column -> com.llf.ai.api.dto.DbQueryResultDTO.ColumnDTO.builder()
                        .ordinal(column.getOrdinal()).label(column.getLabel()).typeName(column.getTypeName())
                        .jdbcType(column.getJdbcType()).nullable(column.isNullable()).precision(column.getPrecision())
                        .scale(column.getScale()).build()).toList())
                .rows(result.getRows()).rowCount(result.getRowCount())
                .affectedRows(result.getAffectedRows() == null ? null : String.valueOf(result.getAffectedRows()))
                .affectedRowsSemantics(result.getAffectedRowsSemantics()).truncated(result.isTruncated())
                .truncationReasons(result.getTruncationReasons()).resultBytes(result.getResultBytes())
                .warnings(result.getWarnings()).sqlState(result.getSqlState()).vendorCode(result.getVendorCode())
                .safeError(result.getSafeError()).queueMillis(result.getQueueMillis())
                .executionMillis(result.getExecutionMillis()).totalMillis(result.getTotalMillis())
                .autoCommit(result.isAutoCommit()).transactionState(result.getTransactionState())
                .currentDatabase(result.getCurrentDatabase()).sessionTimeZone(result.getSessionTimeZone())
                .sessionReusable(result.isSessionReusable())
                .capabilityState(result.getCapabilityState() == null ? null : result.getCapabilityState().name()).build();
    }

    private DbSessionOpenResponseDTO toSession(DbSessionEntity s) {
        return DbSessionOpenResponseDTO.builder().dbSessionId(s.getDbSessionId())
                .sessionGeneration(s.getSessionGeneration()).connectionId(s.getConnectionId())
                .configVersion(s.getConfigVersion()).currentDatabase(s.getCurrentDatabase())
                .targetContextVersion(s.getTargetContextVersion()).lifecycleStatus(s.getLifecycleStatus().name())
                .consoleLaneStatus(s.getConsoleLaneStatus().name()).aiLaneStatus(s.getAiLaneStatus().name())
                .autoCommit(s.isAutoCommit()).transactionState(s.getTransactionState())
                .sessionTimeZone(s.getSessionTimeZone()).serverVersion(s.getServerVersion())
                .tlsEncrypted(s.isTlsEncrypted()).tlsIdentityVerified(s.isTlsIdentityVerified())
                .capabilityStates(s.getCapabilityStates().entrySet().stream().collect(
                        java.util.stream.Collectors.toUnmodifiableMap(java.util.Map.Entry::getKey, e -> e.getValue().name())))
                .build();
    }

    private <T> Response<T> success(T data) {
        return Response.<T>builder().code(ResponseCode.SUCCESS.getCode()).info(ResponseCode.SUCCESS.getInfo()).data(data).build();
    }

    private <T> Response<T> failure(ResponseCode code, String info) {
        return Response.<T>builder().code(code.getCode()).info(info == null ? code.getInfo() : info).build();
    }
}
