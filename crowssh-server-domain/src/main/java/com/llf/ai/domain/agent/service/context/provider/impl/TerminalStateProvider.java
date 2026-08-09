package com.llf.ai.domain.agent.service.context.provider.impl;

import com.llf.ai.domain.agent.service.context.provider.ContextProvider;
import com.llf.ai.domain.ssh.service.ISshTerminalService;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 终端状态上下文提供者（order=10，最先执行）
 * <p>
 * 功能：通过 SSH 终端实时采集远程服务器的环境信息（OS/用户/工作目录/运行时长），
 * 让模型"知道自己在哪台机器上操作"。该逻辑从 PromptService 下沉至此。
 * <p>
 * 运行过程：
 * <pre>
 *   provide(sessionId, ownerId, terminalSessionId, history)
 *        |
 *        | terminalSessionId 为空 ? --> 返回空 Map（无终端则不采集）
 *        v
 *   safeExec(ownerId, terminalSessionId, cmd)  逐条执行采集命令
 *        |
 *        +-- "uname -srm"  --> osInfo          （操作系统/架构）
 *        +-- "whoami"      --> currentUser     （当前登录用户）
 *        +-- "pwd"         --> currentDirectory（当前工作目录）
 *        +-- "uptime"      --> uptime          （运行时长）
 *        |
 *        v
 *   Map{osInfo, currentUser, currentDirectory, uptime}
 *        |
 *        v
 *   ChatContextService 合并 --> PromptContextVO --> 消息前缀 [系统环境]
 * </pre>
 * 容错设计：每条命令独立 try-catch（safeExec），单条失败仅该字段留空，
 * 环境采集是"锦上添花"，绝不阻断主流程。
 *
 * @author llf
 */
@Component
public class TerminalStateProvider implements ContextProvider {

    @Resource
    private ISshTerminalService sshTerminalService;

    @Override
    public String getName() {
        return "terminal-state";
    }

    @Override
    public int getOrder() {
        return 10;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public Map<String, Object> provide(String sessionId, String ownerId, String terminalSessionId,
                                       List<Map<String, Object>> messageHistory) {
        Map<String, Object> result = new HashMap<>();

        if (ownerId == null || ownerId.isBlank()
                || terminalSessionId == null || terminalSessionId.isBlank()) {
            return result;
        }

        String osInfo = safeExec(ownerId, terminalSessionId, "uname -srm");
        String user = safeExec(ownerId, terminalSessionId, "whoami");
        String pwd = safeExec(ownerId, terminalSessionId, "pwd");
        String uptime = safeExec(ownerId, terminalSessionId, "uptime -p 2>/dev/null || uptime");

        result.put("osInfo", osInfo);
        result.put("currentUser", user);
        result.put("currentDirectory", pwd);
        result.put("uptime", uptime);
        return result;
    }

    private String safeExec(String ownerId, String terminalSessionId, String cmd) {
        try {
            String res = sshTerminalService.executeCommand(ownerId, terminalSessionId, cmd);
            return res != null ? res.trim() : "";
        } catch (Exception e) {
            return "";
        }
    }
}
