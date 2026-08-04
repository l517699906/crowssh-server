package com.llf.ai.trigger.http;

import com.llf.ai.api.dto.SshConnectionRequestDTO;
import com.llf.ai.api.dto.SshConnectionResponseDTO;
import com.llf.ai.api.response.Response;
import com.llf.ai.domain.ssh.model.entity.SshConnectionConfigEntity;
import com.llf.ai.domain.ssh.model.entity.SshConnectionEntity;
import com.llf.ai.domain.ssh.model.valobj.AuthTypeEnum;
import com.llf.ai.domain.ssh.model.valobj.ConnectionStatusEnum;
import com.llf.ai.domain.ssh.service.ISshConnectionService;
import com.llf.ai.types.enums.ResponseCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.security.Principal;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

/**
 * SSH连接管理 HTTP控制器
 *
 * @author llf
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/ssh")
public class SshConnectionController {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Resource
    private ISshConnectionService sshConnectionDomainService;

    @RequestMapping(value = "create_connection", method = RequestMethod.POST)
    public Response<SshConnectionResponseDTO> createConnection(
            @RequestBody SshConnectionRequestDTO requestDTO,
            Principal principal
    ) {
        try {
            log.info("创建SSH连接 name={} host={}", requestDTO.getConnectionName(), requestDTO.getHost());

            SshConnectionEntity entity = toEntity(requestDTO);
            SshConnectionConfigEntity configEntity = toConfigEntity(requestDTO);

            sshConnectionDomainService.createConnection(principal.getName(), entity, configEntity);

            return Response.<SshConnectionResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(toResponseDTO(entity, configEntity))
                    .build();
        } catch (IllegalArgumentException e) {
            log.warn("创建SSH连接参数错误: {}", e.getMessage());
            return Response.<SshConnectionResponseDTO>builder()
                    .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                    .info(e.getMessage())
                    .build();
        } catch (Exception e) {
            log.error("创建SSH连接失败", e);
            return Response.<SshConnectionResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @RequestMapping(value = "update_connection", method = RequestMethod.POST)
    public Response<SshConnectionResponseDTO> updateConnection(
            @RequestBody SshConnectionRequestDTO requestDTO,
            Principal principal
    ) {
        try {
            log.info("更新SSH连接 connectionId={}", requestDTO.getConnectionId());

            SshConnectionEntity entity = toEntity(requestDTO);
            SshConnectionConfigEntity configEntity = toConfigEntity(requestDTO);

            String ownerId = principal.getName();
            sshConnectionDomainService.updateConnection(ownerId, entity, configEntity);

            // 查询更新后的完整数据返回
            SshConnectionEntity updated = sshConnectionDomainService.getConnection(
                    ownerId, entity.getConnectionId());

            return Response.<SshConnectionResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(toResponseDTO(updated, sshConnectionDomainService.getConnectionConfig(
                            ownerId, updated.getConnectionId())))
                    .build();
        } catch (IllegalArgumentException e) {
            log.warn("更新SSH连接参数错误: {}", e.getMessage());
            return Response.<SshConnectionResponseDTO>builder()
                    .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                    .info(e.getMessage())
                    .build();
        } catch (Exception e) {
            log.error("更新SSH连接失败 connectionId={}", requestDTO.getConnectionId(), e);
            return Response.<SshConnectionResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @RequestMapping(value = "delete_connection", method = RequestMethod.POST)
    public Response<Void> deleteConnection(@RequestParam("connectionId") String connectionId,
                                           Principal principal) {
        try {
            log.info("删除SSH连接 connectionId={}", connectionId);
            sshConnectionDomainService.deleteConnection(principal.getName(), connectionId);

            return Response.<Void>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .build();
        } catch (IllegalArgumentException e) {
            log.warn("删除SSH连接参数错误: {}", e.getMessage());
            return Response.<Void>builder()
                    .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                    .info(e.getMessage())
                    .build();
        } catch (Exception e) {
            log.error("删除SSH连接失败 connectionId={}", connectionId, e);
            return Response.<Void>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @RequestMapping(value = "get_connection", method = RequestMethod.GET)
    public Response<SshConnectionResponseDTO> getConnection(
            @RequestParam("connectionId") String connectionId,
            Principal principal
    ) {
        try {
            log.info("查询SSH连接 connectionId={}", connectionId);
            String ownerId = principal.getName();
            SshConnectionEntity entity = sshConnectionDomainService.getConnection(ownerId, connectionId);

            if (entity == null) {
                return Response.<SshConnectionResponseDTO>builder()
                        .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                        .info("连接不存在")
                        .build();
            }

            return Response.<SshConnectionResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(toResponseDTO(entity, sshConnectionDomainService.getConnectionConfig(
                            ownerId, entity.getConnectionId())))
                    .build();
        } catch (Exception e) {
            log.error("查询SSH连接失败 connectionId={}", connectionId, e);
            return Response.<SshConnectionResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @RequestMapping(value = "connection_list", method = RequestMethod.GET)
    public Response<List<SshConnectionResponseDTO>> getConnectionList(Principal principal) {
        String ownerId = principal.getName();
        try {
            log.info("查询SSH连接列表 ownerId={}", ownerId);
            List<SshConnectionEntity> entities = sshConnectionDomainService.getConnectionList(ownerId);

            // 同步实际的连接状态
            List<SshConnectionResponseDTO> dtoList = entities.stream()
                    .map(entity -> {
                        // 检查实际的 SSH 连接状态
                        boolean actuallyConnected = sshConnectionDomainService.isConnected(
                                ownerId, entity.getConnectionId());
                        if (actuallyConnected && entity.getStatus() != ConnectionStatusEnum.CONNECTED) {
                            entity.setStatus(ConnectionStatusEnum.CONNECTED);
                        } else if (!actuallyConnected && entity.getStatus() == ConnectionStatusEnum.CONNECTED) {
                            entity.setStatus(ConnectionStatusEnum.DISCONNECTED);
                        }
                        return toResponseDTO(entity, sshConnectionDomainService.getConnectionConfig(
                                ownerId, entity.getConnectionId()));
                    })
                    .collect(Collectors.toList());

            return Response.<List<SshConnectionResponseDTO>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(dtoList)
                    .build();
        } catch (Exception e) {
            log.error("查询SSH连接列表失败 ownerId={}", ownerId, e);
            return Response.<List<SshConnectionResponseDTO>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @RequestMapping(value = "test_connection", method = RequestMethod.POST)
    public Response<Void> testConnection(@RequestBody SshConnectionRequestDTO requestDTO) {
        try {
            log.info("测试SSH连接 host={}:{} user={}",
                    requestDTO.getHost(), requestDTO.getPort(), requestDTO.getUsername());
            sshConnectionDomainService.testConnection(
                    toEntity(requestDTO),
                    toConfigEntity(requestDTO));

            return Response.<Void>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info("连接和认证均成功")
                    .build();
        } catch (IllegalArgumentException e) {
            log.warn("测试SSH连接参数错误: {}", e.getMessage());
            return Response.<Void>builder()
                    .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                    .info(e.getMessage())
                    .build();
        } catch (IllegalStateException e) {
            log.warn("测试SSH连接失败 host={}:{} error={}",
                    requestDTO.getHost(), requestDTO.getPort(), e.getMessage());
            return Response.<Void>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(e.getMessage())
                    .build();
        } catch (Exception e) {
            log.error("测试SSH连接异常 host={}:{}", requestDTO.getHost(), requestDTO.getPort(), e);
            return Response.<Void>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("连接测试失败: " + e.getMessage())
                    .build();
        }
    }

    @RequestMapping(value = "connect", method = RequestMethod.POST)
    public Response<Void> connect(@RequestParam("connectionId") String connectionId,
                                  Principal principal) {
        try {
            log.info("建立SSH连接 connectionId={}", connectionId);
            boolean success = sshConnectionDomainService.connect(principal.getName(), connectionId);

            if (success) {
                return Response.<Void>builder()
                        .code(ResponseCode.SUCCESS.getCode())
                        .info("连接成功")
                        .build();
            } else {
                return Response.<Void>builder()
                        .code(ResponseCode.UN_ERROR.getCode())
                        .info("连接失败，请检查主机地址、端口和认证信息")
                        .build();
            }
        } catch (IllegalArgumentException e) {
            log.warn("建立SSH连接参数错误: {}", e.getMessage());
            return Response.<Void>builder()
                    .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                    .info(e.getMessage())
                    .build();
        } catch (Exception e) {
            log.error("建立SSH连接失败 connectionId={}", connectionId, e);
            return Response.<Void>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("连接失败: " + e.getMessage())
                    .build();
        }
    }

    @RequestMapping(value = "disconnect", method = RequestMethod.POST)
    public Response<Void> disconnect(@RequestParam("connectionId") String connectionId,
                                     Principal principal) {
        try {
            log.info("断开SSH连接 connectionId={}", connectionId);
            sshConnectionDomainService.disconnect(principal.getName(), connectionId);

            return Response.<Void>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info("已断开连接")
                    .build();
        } catch (Exception e) {
            log.error("断开SSH连接失败 connectionId={}", connectionId, e);
            return Response.<Void>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("断开连接失败: " + e.getMessage())
                    .build();
        }
    }

    // ========== DTO <-> Entity 转换 ==========

    private SshConnectionEntity toEntity(SshConnectionRequestDTO dto) {
        return SshConnectionEntity.builder()
                .connectionId(dto.getConnectionId())
                .connectionName(dto.getConnectionName())
                .host(dto.getHost())
                .port(dto.getPort())
                .username(dto.getUsername())
                .authType(dto.getAuthType() != null ? AuthTypeEnum.fromCode(dto.getAuthType()) : AuthTypeEnum.PASSWORD)
                .password(dto.getPassword())
                .privateKey(dto.getPrivateKey())
                .build();
    }

    private SshConnectionConfigEntity toConfigEntity(SshConnectionRequestDTO dto) {
        return SshConnectionConfigEntity.builder()
                .connectTimeout(dto.getConnectTimeout())
                .keepaliveInterval(dto.getKeepaliveInterval())
                .startupCommand(dto.getStartupCommand())
                .compression(dto.getCompression())
                .strictHostKeyCheck(dto.getStrictHostKeyCheck())
                .build();
    }

    private SshConnectionResponseDTO toResponseDTO(SshConnectionEntity entity, SshConnectionConfigEntity configEntity) {
        return SshConnectionResponseDTO.builder()
                .connectionId(entity.getConnectionId())
                .connectionName(entity.getConnectionName())
                .host(entity.getHost())
                .port(entity.getPort())
                .username(entity.getUsername())
                .authType(entity.getAuthType() != null ? entity.getAuthType().getCode() : null)
                .status(entity.getStatus() != null ? entity.getStatus().getCode() : null)
                .encrypted(entity.getEncrypted())
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().format(FMT) : null)
                .updatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().format(FMT) : null)
                .connectTimeout(configEntity != null ? configEntity.getConnectTimeout() : null)
                .keepaliveInterval(configEntity != null ? configEntity.getKeepaliveInterval() : null)
                .startupCommand(configEntity != null ? configEntity.getStartupCommand() : null)
                .compression(configEntity != null ? configEntity.getCompression() : null)
                .strictHostKeyCheck(configEntity != null ? configEntity.getStrictHostKeyCheck() : null)
                .build();
    }

}
