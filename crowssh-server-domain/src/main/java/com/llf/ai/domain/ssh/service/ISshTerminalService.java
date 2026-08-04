package com.llf.ai.domain.ssh.service;

import com.llf.ai.domain.ssh.adapter.port.CommandExecutionResult;
import com.llf.ai.domain.ssh.adapter.port.TerminalSessionEntity;

/**
 * SSH终端领域服务接口
 * 定义终端会话的核心业务操作
 *
 * @author llf
 */
public interface ISshTerminalService {

    /**
     * 打开终端会话
     *
     * @param connectionId SSH连接ID
     * @param cols          终端列数
     * @param rows          终端行数
     * @return 终端会话实体
     */
    TerminalSessionEntity openTerminal(String ownerId, String connectionId, int cols, int rows);

    /**
     * 执行命令并返回输出
     *
     * @param sessionId 会话ID
     * @param command    命令内容
     * @return 命令执行后的终端输出
     */
    String executeCommand(String ownerId, String sessionId, String command);

    /**
     * 在隔离执行通道中执行命令并返回退出码，供 AI 工具判断命令是否真正成功。
     * 旧实现默认将退出码视为未知；调用方不得将未知退出码判定为成功。
     */
    default CommandExecutionResult executeCommandWithResult(
            String ownerId, String sessionId, String command) {
        return new CommandExecutionResult(executeCommand(ownerId, sessionId, command), 0, false);
    }

    /**
     * 调整终端大小
     *
     * @param sessionId 会话ID
     * @param cols       新的列数
     * @param rows       新的行数
     */
    void resizeTerminal(String ownerId, String sessionId, int cols, int rows);

    /**
     * 获取终端会话
     *
     * @param sessionId 会话ID
     * @return 终端会话实体
     */
    TerminalSessionEntity getTerminalSession(String ownerId, String sessionId);

    /**
     * 关闭终端会话
     *
     * @param sessionId 会话ID
     */
    void closeTerminal(String ownerId, String sessionId);

    /**
     * 检查会话是否存在
     *
     * @param sessionId 会话ID
     * @return 是否存在
     */
    boolean sessionExists(String ownerId, String sessionId);

    /**
     * 读取终端当前输出（不执行命令，用于同步状态）
     *
     * @param sessionId 会话ID
     * @return 当前终端输出
     */
    String readTerminal(String ownerId, String sessionId);

    /**
     * 写入原始输入到终端（逐字节模式，由 Shell 自身处理 echo）
     *
     * @param sessionId 会话ID
     * @param input     原始输入数据
     */
    void writeTerminal(String ownerId, String sessionId, String input);

}
