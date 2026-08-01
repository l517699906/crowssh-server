package com.llf.ai.domain.ssh.service.sftp;

/**
 * 保存前发现远程文件已被其他程序修改。
 */
public class SftpVersionConflictException extends RuntimeException {

    public SftpVersionConflictException(String message) {
        super(message);
    }
}
