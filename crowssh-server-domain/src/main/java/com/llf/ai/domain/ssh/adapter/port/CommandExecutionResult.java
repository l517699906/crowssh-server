package com.llf.ai.domain.ssh.adapter.port;

/**
 * 命令执行结果。
 *
 * @param output         命令标准输出和标准错误合并后的内容
 * @param exitCode       命令退出码；超时时为 -1
 * @param timedOut       是否超过规定的执行时间
 * @param exitCodeKnown  是否由 SSH exec channel 明确返回了退出码
 */
public record CommandExecutionResult(
        String output,
        int exitCode,
        boolean timedOut,
        boolean exitCodeKnown
) {

    public CommandExecutionResult {
        output = output == null ? "" : output;
    }

    /**
     * 兼容旧的终端适配器：旧实现只能返回文本，无法确认退出码。
     */
    public CommandExecutionResult(String output, int exitCode, boolean timedOut) {
        this(output, exitCode, timedOut, false);
    }

    public boolean isSuccess() {
        return exitCodeKnown && !timedOut && exitCode == 0;
    }
}
