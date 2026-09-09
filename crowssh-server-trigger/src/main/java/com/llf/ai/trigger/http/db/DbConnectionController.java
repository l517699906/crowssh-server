package com.llf.ai.trigger.http.db;

import com.llf.ai.api.dto.DbAllowedColumnDTO;
import com.llf.ai.api.dto.DbConnectionRequestDTO;
import com.llf.ai.api.dto.DbConnectionResponseDTO;
import com.llf.ai.api.dto.DbTestConnectionResponseDTO;
import com.llf.ai.api.response.Response;
import com.llf.ai.domain.db.adapter.port.DbTargetBlockedException;
import com.llf.ai.domain.db.adapter.port.IDbSessionPort;
import com.llf.ai.domain.db.model.entity.DbConnectionEntity;
import com.llf.ai.domain.db.model.valobj.AiDataModeEnum;
import com.llf.ai.domain.db.model.valobj.CaCertificateActionEnum;
import com.llf.ai.domain.db.model.valobj.DbAllowedColumn;
import com.llf.ai.domain.db.model.valobj.DbTypeEnum;
import com.llf.ai.domain.db.model.valobj.PasswordActionEnum;
import com.llf.ai.domain.db.model.valobj.SslModeEnum;
import com.llf.ai.domain.db.service.IDbConnectionService;
import com.llf.ai.domain.db.service.connection.DbConfigConflictException;
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
@RequestMapping("/api/v1/db")
public class DbConnectionController {
    private final IDbConnectionService connectionService;

    public DbConnectionController(IDbConnectionService connectionService) {
        this.connectionService = connectionService;
    }

    @PostMapping("create_connection")
    public Response<DbConnectionResponseDTO> create(@RequestBody DbConnectionRequestDTO request,
                                                    Principal principal) {
        try {
            DbConnectionEntity entity = connectionService.create(principal.getName(), toEntity(request),
                    passwordAction(request), caAction(request));
            return success(toResponse(entity));
        } catch (IllegalArgumentException e) {
            return failure(ResponseCode.ILLEGAL_PARAMETER, e.getMessage());
        } catch (Exception e) {
            log.error("创建数据库连接失败 exceptionType={}", e.getClass().getSimpleName());
            return failure(ResponseCode.UN_ERROR, "创建数据库连接失败");
        }
    }

    @PostMapping("update_connection")
    public Response<DbConnectionResponseDTO> update(@RequestBody DbConnectionRequestDTO request,
                                                    Principal principal) {
        try {
            if (request.getExpectedConfigVersion() == null) throw new IllegalArgumentException("expectedConfigVersion 不能为空");
            DbConnectionEntity entity = connectionService.update(principal.getName(), toEntity(request),
                    request.getExpectedConfigVersion(), passwordAction(request), caAction(request));
            return success(toResponse(entity));
        } catch (DbConfigConflictException e) {
            return failure(ResponseCode.DB_CONFIG_CONFLICT, e.getMessage());
        } catch (IllegalArgumentException e) {
            return failure(ResponseCode.ILLEGAL_PARAMETER, e.getMessage());
        } catch (Exception e) {
            log.error("更新数据库连接失败 exceptionType={}", e.getClass().getSimpleName());
            return failure(ResponseCode.UN_ERROR, "更新数据库连接失败");
        }
    }

    @PostMapping("delete_connection")
    public Response<Void> delete(@RequestParam String connectionId, Principal principal) {
        try {
            connectionService.delete(principal.getName(), connectionId);
            return Response.<Void>builder().code(ResponseCode.SUCCESS.getCode()).info(ResponseCode.SUCCESS.getInfo()).build();
        } catch (IllegalArgumentException e) {
            return Response.<Void>builder().code(ResponseCode.ILLEGAL_PARAMETER.getCode()).info(e.getMessage()).build();
        }
    }

    @GetMapping("get_connection")
    public Response<DbConnectionResponseDTO> get(@RequestParam String connectionId, Principal principal) {
        try {
            return success(toResponse(connectionService.get(principal.getName(), connectionId)));
        } catch (IllegalArgumentException e) {
            return failure(ResponseCode.ILLEGAL_PARAMETER, "连接不存在");
        }
    }

    @GetMapping("connection_list")
    public Response<List<DbConnectionResponseDTO>> list(Principal principal) {
        try {
            return success(connectionService.list(principal.getName()).stream().map(this::toResponse).toList());
        } catch (IllegalArgumentException e) {
            return failure(ResponseCode.ILLEGAL_PARAMETER, e.getMessage());
        }
    }

    @PostMapping("test_connection")
    public Response<DbTestConnectionResponseDTO> test(@RequestBody DbConnectionRequestDTO request,
                                                      Principal principal) {
        try {
            IDbSessionPort.TestConnectionResult result = connectionService.test(
                    principal.getName(), toEntity(request), request.getConnectionId(),
                    passwordAction(request), caAction(request));
            return success(DbTestConnectionResponseDTO.builder()
                    .serverVersion(result.serverVersion())
                    .capabilities(List.of("MYSQL"))
                    .capabilityStates(result.capabilityStates().entrySet().stream().collect(
                            java.util.stream.Collectors.toMap(java.util.Map.Entry::getKey, item -> item.getValue().name())))
                    .tlsEncrypted(result.tlsEncrypted())
                    .tlsIdentityVerified(result.tlsIdentityVerified())
                    .sessionTimeZone(result.sessionTimeZone())
                    .build());
        } catch (DbTargetBlockedException e) {
            return failure(ResponseCode.DB_TARGET_BLOCKED, e.getMessage());
        } catch (IllegalArgumentException e) {
            return failure(ResponseCode.ILLEGAL_PARAMETER, e.getMessage());
        } catch (com.llf.ai.domain.db.adapter.port.DbTlsVerificationException e) {
            return failure(ResponseCode.DB_TLS_VERIFICATION_FAILED, e.getMessage());
        } catch (IllegalStateException e) {
            return failure(ResponseCode.DB_CONNECTION_FAILED, e.getMessage());
        }
    }

    private DbConnectionEntity toEntity(DbConnectionRequestDTO dto) {
        return DbConnectionEntity.builder()
                .connectionId(dto.getConnectionId())
                .connectionName(dto.getConnectionName())
                .dbType(parseEnum(dto.getDbType(), DbTypeEnum.class, null))
                .host(dto.getHost())
                .port(dto.getPort())
                .username(dto.getUsername())
                .password(dto.getPassword())
                .defaultDatabase(dto.getDefaultDatabase())
                .sslMode(parseEnum(dto.getSslMode(), SslModeEnum.class, null))
                .tlsServerName(dto.getTlsServerName())
                .caCertificatePem(dto.getCaCertificatePem())
                .tunnelSshConnectionId(dto.getTunnelSshConnectionId())
                .connectTimeout(dto.getConnectTimeout())
                .queryTimeout(dto.getQueryTimeout())
                .maxRows(dto.getMaxRows())
                .aiDataMode(parseEnum(dto.getAiDataMode(), AiDataModeEnum.class, null))
                .aiAllowedColumns(dto.getAiAllowedColumns() == null ? List.of() : dto.getAiAllowedColumns().stream()
                        .map(item -> new DbAllowedColumn(item.getDatabase(), item.getTable(), item.getColumn())).toList())
                .build();
    }

    private DbConnectionResponseDTO toResponse(DbConnectionEntity entity) {
        return DbConnectionResponseDTO.builder()
                .connectionId(entity.getConnectionId()).connectionName(entity.getConnectionName())
                .dbType(entity.getDbType().name()).host(entity.getHost()).port(entity.getPort())
                .username(entity.getUsername()).defaultDatabase(entity.getDefaultDatabase())
                .sslMode(entity.getSslMode().name()).tlsServerName(entity.getTlsServerName())
                .tunnelSshConnectionId(entity.getTunnelSshConnectionId())
                .connectTimeout(entity.getConnectTimeout()).queryTimeout(entity.getQueryTimeout())
                .maxRows(entity.getMaxRows()).configVersion(entity.getConfigVersion())
                .aiDataMode(entity.getAiDataMode().name())
                .aiAllowedColumns(entity.getAiAllowedColumns() == null ? List.of() : entity.getAiAllowedColumns().stream()
                        .map(item -> {
                            DbAllowedColumnDTO dto = new DbAllowedColumnDTO();
                            dto.setDatabase(item.database()); dto.setTable(item.table()); dto.setColumn(item.column());
                            return dto;
                        }).toList())
                .hasPassword(entity.hasPassword()).hasCaCertificate(entity.hasCaCertificate())
                .status(entity.getStatus()).createdAt(entity.getCreatedAt() == null ? null : entity.getCreatedAt().toString())
                .updatedAt(entity.getUpdatedAt() == null ? null : entity.getUpdatedAt().toString())
                .build();
    }

    private PasswordActionEnum passwordAction(DbConnectionRequestDTO dto) {
        return parseEnum(dto.getPasswordAction(), PasswordActionEnum.class, null);
    }

    private CaCertificateActionEnum caAction(DbConnectionRequestDTO dto) {
        return parseEnum(dto.getCaCertificateAction(), CaCertificateActionEnum.class, null);
    }

    private <T extends Enum<T>> T parseEnum(String value, Class<T> type, T fallback) {
        if (value == null || value.isBlank()) return fallback;
        return Enum.valueOf(type, value.trim().toUpperCase());
    }

    private <T> Response<T> success(T data) {
        return Response.<T>builder().code(ResponseCode.SUCCESS.getCode()).info(ResponseCode.SUCCESS.getInfo()).data(data).build();
    }

    private <T> Response<T> failure(ResponseCode code, String info) {
        return Response.<T>builder().code(code.getCode()).info(info == null ? code.getInfo() : info).build();
    }
}
