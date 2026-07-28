package com.llf.ai.domain.ssh.adapter.port;

/**
 * 终端会话服务接口
 * 负责管理 SSH 终端会话，包括打开/写入/读取/调整大小/关闭会话
 *
 * @author llf
 */
public interface ITerminalSessionPort {

    /**
     * 打开终端会话
     *
     * @param connectionId SSH连接ID
     * @param cols         终端列数
     * @param rows         终端行数
     * @return 会话ID
     */
    String openTerminal(String connectionId, int cols, int rows);

    /**
     * 写入命令到终端
     *
     * @param sessionId 会话ID
     * @param command   命令内容
     */
    void write(String sessionId, String command);

    /**
     * 读取终端输出
     *
     * @param sessionId 会话ID
     * @return 终端输出内容
     */
    String read(String sessionId);

    /**
     * 执行命令并等待输出完成（用于 AI 工具调用）
     * <p>
     * 写入命令 + 换行符，等待 Shell prompt 重新出现后返回完整输出。
     * 与 write+read 不同，此方法会阻塞等待命令执行完成。
     *
     * @param sessionId 会话ID
     * @param command   命令内容（不含换行符）
     * @param timeoutMs 超时时间（毫秒）
     * @return 命令执行后的完整终端输出
     */
    String executeCommandAndWait(String sessionId, String command, long timeoutMs);

    /**
     * 调整终端大小
     *
     * @param sessionId 会话ID
     * @param cols      新的列数
     * @param rows      新的行数
     */
    void resize(String sessionId, int cols, int rows);

    /**
     * 关闭终端会话
     *
     * @param sessionId 会话ID
     */
    void closeSession(String sessionId);

    /**
     * 检查会话是否存在
     *
     * @param sessionId 会话ID
     * @return 是否存在
     */
    boolean sessionExists(String sessionId);

}
