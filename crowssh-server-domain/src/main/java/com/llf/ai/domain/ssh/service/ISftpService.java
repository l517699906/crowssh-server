package com.llf.ai.domain.ssh.service;

import com.llf.ai.domain.ssh.adapter.port.SftpFileEntity;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * SFTP 领域服务。
 */
public interface ISftpService {

    String resolvePath(String connectionId, String path);

    List<SftpFileEntity> list(String connectionId, String path);

    void upload(String connectionId, String remotePath, String fileName,
                long size, InputStream inputStream);

    void download(String connectionId, String remotePath, OutputStream outputStream);

    long fileSize(String connectionId, String remotePath);
}
