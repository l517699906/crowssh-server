package com.llf.ai.domain.ssh.adapter.port;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * SFTP 基础设施端口。
 */
public interface ISftpSessionPort {

    String canonicalize(String connectionId, String path);

    List<SftpFileEntity> list(String connectionId, String path);

    void upload(String connectionId, String remotePath, String fileName,
                long size, InputStream inputStream);

    void download(String connectionId, String remotePath, OutputStream outputStream);

    long fileSize(String connectionId, String remotePath);
}
