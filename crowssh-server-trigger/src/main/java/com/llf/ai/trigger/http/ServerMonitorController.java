package com.llf.ai.trigger.http;

import com.llf.ai.api.dto.ServerMonitorSnapshotDTO;
import com.llf.ai.api.response.Response;
import com.llf.ai.domain.ssh.adapter.port.ServerMonitorUnavailableException;
import com.llf.ai.domain.ssh.adapter.port.ServerMonitorUnsupportedException;
import com.llf.ai.domain.ssh.model.entity.ServerMonitorSnapshotEntity;
import com.llf.ai.domain.ssh.service.IServerMonitorService;
import com.llf.ai.types.enums.ResponseCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;

/**
 * 当前 SSH 连接的服务器监控接口。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/ssh/monitor")
public class ServerMonitorController {

    private final IServerMonitorService serverMonitorService;

    public ServerMonitorController(IServerMonitorService serverMonitorService) {
        this.serverMonitorService = serverMonitorService;
    }

    @GetMapping("/snapshot")
    public Response<ServerMonitorSnapshotDTO> snapshot(
            @RequestParam("connectionId") String connectionId,
            Principal principal) {
        try {
            ServerMonitorSnapshotEntity snapshot = serverMonitorService.getSnapshot(
                    principal.getName(), connectionId);
            return response(ResponseCode.SUCCESS, ResponseCode.SUCCESS.getInfo(), toDto(snapshot));
        } catch (ServerMonitorUnsupportedException exception) {
            log.info("SSH 主机不支持监控 connectionId={} reason={}", connectionId, exception.getMessage());
            return response(ResponseCode.SSH_MONITOR_UNSUPPORTED, exception.getMessage(), null);
        } catch (ServerMonitorUnavailableException exception) {
            log.warn("SSH 主机监控暂时不可用 connectionId={} reason={}",
                    connectionId, exception.getMessage());
            return response(ResponseCode.SSH_MONITOR_UNAVAILABLE, exception.getMessage(), null);
        } catch (IllegalArgumentException exception) {
            log.warn("SSH 主机监控参数错误 connectionId={} reason={}",
                    connectionId, exception.getMessage());
            return response(ResponseCode.ILLEGAL_PARAMETER, exception.getMessage(), null);
        } catch (Exception exception) {
            log.error("SSH 主机监控失败 connectionId={}", connectionId, exception);
            return response(ResponseCode.SSH_MONITOR_UNAVAILABLE, "服务器监控暂时不可用", null);
        }
    }

    private ServerMonitorSnapshotDTO toDto(ServerMonitorSnapshotEntity entity) {
        ServerMonitorSnapshotEntity.Host host = entity.host();
        ServerMonitorSnapshotEntity.Cpu cpu = entity.cpu();
        ServerMonitorSnapshotEntity.Load load = entity.load();
        ServerMonitorSnapshotEntity.Memory memory = entity.memory();
        ServerMonitorSnapshotEntity.Disk disk = entity.disk();
        ServerMonitorSnapshotEntity.Network network = entity.network();
        return new ServerMonitorSnapshotDTO(
                entity.capturedAtEpochMs(),
                new ServerMonitorSnapshotDTO.Host(
                        host.hostname(), host.osName(), host.osVersion(),
                        host.kernelVersion(), host.architecture()),
                entity.uptimeSeconds(),
                new ServerMonitorSnapshotDTO.Cpu(
                        cpu.logicalProcessors(), cpu.totalTicks(), cpu.idleTicks()),
                new ServerMonitorSnapshotDTO.Load(
                        load.oneMinute(), load.fiveMinutes(), load.fifteenMinutes()),
                new ServerMonitorSnapshotDTO.Memory(
                        memory.totalBytes(), memory.availableBytes()),
                disk == null ? null : new ServerMonitorSnapshotDTO.Disk(
                        disk.mountPoint(), disk.totalBytes(), disk.usedBytes(), disk.availableBytes()),
                network == null ? null : new ServerMonitorSnapshotDTO.Network(
                        network.interfaceName(), network.receivedBytes(), network.transmittedBytes()));
    }

    private Response<ServerMonitorSnapshotDTO> response(
            ResponseCode code,
            String info,
            ServerMonitorSnapshotDTO data) {
        return Response.<ServerMonitorSnapshotDTO>builder()
                .code(code.getCode())
                .info(info)
                .data(data)
                .build();
    }
}
