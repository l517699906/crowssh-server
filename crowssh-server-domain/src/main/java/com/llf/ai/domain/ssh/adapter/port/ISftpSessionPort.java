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

    void rename(String connectionId, String remotePath, String newName);

    void createDirectory(String connectionId, String remotePath, String name);

    void createFile(String connectionId, String remotePath, String name);

    void archive(String connectionId, String remotePath, String archiveName);

    void extract(String connectionId, String remotePath, String directoryName);

    void delete(String connectionId, String remotePath);

    void chmod(String connectionId, String remotePath, int permissions);

    SftpContentEntity readContent(String connectionId, String remotePath, long maxSize);

    SftpContentEntity writeContent(String connectionId, String remotePath, byte[] content,
                                   String expectedVersion, long maxSize);
}
