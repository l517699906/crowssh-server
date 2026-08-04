package com.llf.ai.domain.ssh.service;

import com.llf.ai.domain.ssh.adapter.port.SftpFileEntity;
import com.llf.ai.domain.ssh.service.sftp.SftpTextContentEntity;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * SFTP 领域服务。
 */
public interface ISftpService {

    String resolvePath(String ownerId, String connectionId, String path);

    List<SftpFileEntity> list(String ownerId, String connectionId, String path);

    void upload(String ownerId, String connectionId, String remotePath, String fileName,
                long size, InputStream inputStream);

    void download(String ownerId, String connectionId, String remotePath, OutputStream outputStream);

    long fileSize(String ownerId, String connectionId, String remotePath);

    void rename(String ownerId, String connectionId, String remotePath, String newName);

    void createDirectory(String ownerId, String connectionId, String remotePath, String name);

    void createFile(String ownerId, String connectionId, String remotePath, String name);

    void archive(String ownerId, String connectionId, String remotePath, String archiveName);

    void extract(String ownerId, String connectionId, String remotePath, String directoryName);

    void delete(String ownerId, String connectionId, String remotePath);

    void chmod(String ownerId, String connectionId, String remotePath, String permissions);

    SftpTextContentEntity readText(String ownerId, String connectionId, String remotePath);

    SftpTextContentEntity saveText(String ownerId, String connectionId, String remotePath, String content,
                                   String expectedVersion, String encoding, String lineEnding);
}
