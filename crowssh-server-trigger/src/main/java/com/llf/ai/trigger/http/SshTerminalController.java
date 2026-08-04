package com.llf.ai.trigger.http;

import com.llf.ai.api.dto.*;
import com.llf.ai.api.response.Response;
import com.llf.ai.domain.ssh.adapter.port.TerminalSessionEntity;
import com.llf.ai.domain.ssh.service.ISshTerminalService;
import com.llf.ai.types.enums.ResponseCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.security.Principal;

/**
 * SSH终端操作 HTTP 控制器
 * 提供终端会话的打开、原始I/O读写、大小调整、关闭等能力
 *
 * @author llf
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/ssh/terminal")
public class SshTerminalController {

    @Resource
    private ISshTerminalService sshTerminalDomainService;

    @RequestMapping(value = "open", method = RequestMethod.POST)
    public Response<TerminalOpenResponseDTO> openTerminal(
            @RequestBody TerminalOpenRequestDTO requestDTO,
            Principal principal
    ) {
        try {
            log.info("打开终端会话 connectionId={}", requestDTO.getConnectionId());

            int cols = requestDTO.getCols() != null ? requestDTO.getCols() : 120;
            int rows = requestDTO.getRows() != null ? requestDTO.getRows() : 24;

            TerminalSessionEntity entity = sshTerminalDomainService.openTerminal(
                    principal.getName(), requestDTO.getConnectionId(), cols, rows);

            // 等待 MOTD 积累完后 drain 缓冲区，作为 initialOutput 返回
            // 这样前端不依赖轮询获取初始输出，避免时序问题导致"有时显示有时不显示"
            String initialOutput = waitForInitialOutput(
                    principal.getName(), entity.getSessionId(), 2000);

            TerminalOpenResponseDTO response = TerminalOpenResponseDTO.builder()
                    .sessionId(entity.getSessionId())
                    .connectionId(entity.getConnectionId())
                    .initialOutput(initialOutput)
                    .build();

            return Response.<TerminalOpenResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(response)
                    .build();
        } catch (IllegalStateException | IllegalArgumentException e) {
            log.warn("打开终端会话参数错误: {}", e.getMessage());
            return Response.<TerminalOpenResponseDTO>builder()
                    .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                    .info(e.getMessage())
                    .build();
        } catch (Exception e) {
            log.error("打开终端会话失败", e);
            return Response.<TerminalOpenResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("打开终端失败: " + e.getMessage())
                    .build();
        }
    }

    /**
     * 等待并收集 Shell 初始输出（Last login + MOTD + prompt）
     * openTerminal 已等首数据+200ms，这里只需 drain 缓冲区
     * 不做换行符转换，xterm.js 自己处理 \r 和 \n
     */
    private String waitForInitialOutput(String ownerId, String sessionId, long timeoutMs) {
        // drain 缓冲区：openTerminal 已等待首数据+200ms，MOTD 应该已完整
        String output = sshTerminalDomainService.readTerminal(ownerId, sessionId);
        if (output == null || output.isEmpty()) {
            return "";
        }

        // 额外 drain 一次，确保残余数据也拿到
        try {
            Thread.sleep(50);
        } catch (InterruptedException ignored) {
        }
        String more = sshTerminalDomainService.readTerminal(ownerId, sessionId);
        if (more != null && !more.isEmpty()) {
            output += more;
        }

        return output;
    }

    @RequestMapping(value = "exec", method = RequestMethod.POST)
    public Response<TerminalExecResponseDTO> execCommand(
            @RequestBody TerminalExecRequestDTO requestDTO,
            Principal principal
    ) {
        try {
            String output = sshTerminalDomainService.executeCommand(
                    principal.getName(), requestDTO.getSessionId(), requestDTO.getCommand());

            TerminalExecResponseDTO response = TerminalExecResponseDTO.builder()
                    .output(output)
                    .build();

            return Response.<TerminalExecResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(response)
                    .build();
        } catch (IllegalArgumentException e) {
            log.warn("执行命令参数错误: {}", e.getMessage());
            return Response.<TerminalExecResponseDTO>builder()
                    .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                    .info(e.getMessage())
                    .build();
        } catch (Exception e) {
            log.error("执行命令失败 sessionId={}", requestDTO.getSessionId(), e);
            return Response.<TerminalExecResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("执行命令失败: " + e.getMessage())
                    .build();
        }
    }

    @RequestMapping(value = "write", method = RequestMethod.POST)
    public Response<Void> writeToTerminal(
            @RequestBody TerminalWriteRequestDTO requestDTO,
            Principal principal
    ) {
        try {
            sshTerminalDomainService.writeTerminal(
                    principal.getName(), requestDTO.getSessionId(), requestDTO.getInput());
            return Response.<Void>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .build();
        } catch (IllegalArgumentException e) {
            log.warn("写入终端参数错误: {}", e.getMessage());
            return Response.<Void>builder()
                    .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                    .info(e.getMessage())
                    .build();
        } catch (Exception e) {
            log.error("写入终端失败 sessionId={}", requestDTO.getSessionId(), e);
            return Response.<Void>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("写入终端失败: " + e.getMessage())
                    .build();
        }
    }

    @RequestMapping(value = "read", method = RequestMethod.GET)
    public Response<TerminalReadResponseDTO> readFromTerminal(
            @RequestParam("sessionId") String sessionId,
            Principal principal
    ) {
        try {
            String output = sshTerminalDomainService.readTerminal(principal.getName(), sessionId);
            TerminalReadResponseDTO response = TerminalReadResponseDTO.builder()
                    .output(output != null ? output : "")
                    .build();
            return Response.<TerminalReadResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(response)
                    .build();
        } catch (IllegalArgumentException e) {
            log.warn("读取终端参数错误: {}", e.getMessage());
            return Response.<TerminalReadResponseDTO>builder()
                    .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                    .info(e.getMessage())
                    .build();
        } catch (Exception e) {
            log.error("读取终端失败 sessionId={}", sessionId, e);
            return Response.<TerminalReadResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("读取终端失败: " + e.getMessage())
                    .build();
        }
    }

    @RequestMapping(value = "resize", method = RequestMethod.POST)
    public Response<Void> resizeTerminal(
            @RequestBody TerminalResizeRequestDTO requestDTO,
            Principal principal
    ) {
        try {
            sshTerminalDomainService.resizeTerminal(
                    principal.getName(), requestDTO.getSessionId(),
                    requestDTO.getCols(), requestDTO.getRows());

            return Response.<Void>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .build();
        } catch (Exception e) {
            log.error("调整终端大小失败 sessionId={}", requestDTO.getSessionId(), e);
            return Response.<Void>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("调整终端大小失败: " + e.getMessage())
                    .build();
        }
    }

    @RequestMapping(value = "close", method = RequestMethod.POST)
    public Response<Void> closeTerminal(@RequestParam("sessionId") String sessionId,
                                        Principal principal) {
        try {
            log.info("关闭终端会话 sessionId={}", sessionId);
            sshTerminalDomainService.closeTerminal(principal.getName(), sessionId);

            return Response.<Void>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .build();
        } catch (Exception e) {
            log.error("关闭终端会话失败 sessionId={}", sessionId, e);
            return Response.<Void>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("关闭终端会话失败: " + e.getMessage())
                    .build();
        }
    }

}
