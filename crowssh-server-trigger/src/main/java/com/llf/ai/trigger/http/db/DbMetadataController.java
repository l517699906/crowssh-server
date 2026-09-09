package com.llf.ai.trigger.http.db;

import com.llf.ai.api.dto.DbSchemaDTO;
import com.llf.ai.api.response.Response;
import com.llf.ai.domain.db.model.entity.DbSchemaEntity;
import com.llf.ai.domain.db.service.IDbMetadataService;
import com.llf.ai.types.enums.ResponseCode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;

@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "crowssh.db.enabled", havingValue = "true")
@RestController
@RequestMapping("/api/v1/db/metadata")
public class DbMetadataController {
    private final IDbMetadataService metadataService;

    public DbMetadataController(IDbMetadataService metadataService) {
        this.metadataService = metadataService;
    }

    @org.springframework.web.bind.annotation.PostMapping("refresh_capabilities")
    public Response<java.util.Map<String, String>> refreshCapabilities(@RequestParam String dbSessionId, Principal principal) {
        return success(metadataService.refreshCapabilities(principal.getName(), dbSessionId).entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(java.util.Map.Entry::getKey, e -> e.getValue().name())));
    }

    @GetMapping("databases")
    public Response<List<String>> databases(@RequestParam String dbSessionId, Principal principal) {
        return success(metadataService.listDatabases(principal.getName(), dbSessionId));
    }

    @GetMapping("tables")
    public Response<List<DbSchemaDTO>> tables(@RequestParam String dbSessionId,
                                              @RequestParam String database,
                                              Principal principal) {
        return success(metadataService.listTables(principal.getName(), dbSessionId, database).stream()
                .map(this::toSchema).toList());
    }

    @GetMapping("table")
    public Response<DbSchemaDTO> table(@RequestParam String dbSessionId,
                                       @RequestParam String database,
                                       @RequestParam String table,
                                       Principal principal) {
        return success(toSchema(metadataService.describeTable(principal.getName(), dbSessionId, database, table)));
    }

    private DbSchemaDTO toSchema(DbSchemaEntity entity) {
        return DbSchemaDTO.builder().database(entity.getDatabase()).name(entity.getName()).kind(entity.getKind())
                .engine(entity.getEngine()).estimatedRows(entity.getEstimatedRows()).sizeBytes(entity.getSizeBytes())
                .columns(entity.getColumns() == null ? List.of() : entity.getColumns().stream()
                        .map(column -> DbSchemaDTO.ColumnDTO.builder().ordinal(column.getOrdinal()).name(column.getName())
                                .typeName(column.getTypeName()).nullable(column.isNullable()).keyType(column.getKeyType())
                                .indexName(column.getIndexName())
                                .indexes(column.getIndexes().stream().map(index -> new DbSchemaDTO.IndexMemberDTO(
                                        index.name(), index.position(), index.unique(), index.type())).toList())
                                .build()).toList()).build();
    }

    private <T> Response<T> success(T data) {
        return Response.<T>builder().code(ResponseCode.SUCCESS.getCode()).info(ResponseCode.SUCCESS.getInfo()).data(data).build();
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(com.llf.ai.domain.db.adapter.port.DbSessionBusyException.class)
    public Response<Void> busy(com.llf.ai.domain.db.adapter.port.DbSessionBusyException exception) {
        return failure(ResponseCode.DB_SESSION_BUSY, exception.getMessage());
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(IllegalArgumentException.class)
    public Response<Void> invalid(IllegalArgumentException exception) {
        return failure(ResponseCode.ILLEGAL_PARAMETER, exception.getMessage());
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(IllegalStateException.class)
    public Response<Void> unavailable(IllegalStateException exception) {
        return failure(ResponseCode.DB_CONTEXT_CHANGED, exception.getMessage());
    }

    private Response<Void> failure(ResponseCode code, String message) {
        return Response.<Void>builder().code(code.getCode()).info(message == null ? code.getInfo() : message).build();
    }
}
