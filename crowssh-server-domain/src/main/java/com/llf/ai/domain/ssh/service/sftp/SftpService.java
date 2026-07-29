package com.llf.ai.domain.ssh.service.sftp;

import com.llf.ai.domain.ssh.adapter.port.ISftpSessionPort;
import com.llf.ai.domain.ssh.adapter.port.ISshSessionPort;
import com.llf.ai.domain.ssh.adapter.port.SftpFileEntity;
import com.llf.ai.domain.ssh.service.ISftpService;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

@Service
public class SftpService implements ISftpService {

    private final ISshSessionPort sshSessionPort;
    private final ISftpSessionPort sftpSessionPort;

    public SftpService(ISshSessionPort sshSessionPort, ISftpSessionPort sftpSessionPort) {
        this.sshSessionPort = sshSessionPort;
        this.sftpSessionPort = sftpSessionPort;
    }

    @Override
    public String resolvePath(String connectionId, String path) {
        requireConnected(connectionId);
        return sftpSessionPort.canonicalize(connectionId, normalizePath(path));
    }

    @Override
    public List<SftpFileEntity> list(String connectionId, String path) {
        requireConnected(connectionId);
        return sftpSessionPort.list(connectionId, path);
    }

    @Override
    public void upload(String connectionId, String remotePath, String fileName,
                       long size, InputStream inputStream) {
        requireConnected(connectionId);
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("上传文件名不能为空");
        }
        sftpSessionPort.upload(connectionId, remotePath, fileName, size, inputStream);
    }

    @Override
    public void download(String connectionId, String remotePath, OutputStream outputStream) {
        requireConnected(connectionId);
        sftpSessionPort.download(connectionId, remotePath, outputStream);
    }

    @Override
    public long fileSize(String connectionId, String remotePath) {
        requireConnected(connectionId);
        return sftpSessionPort.fileSize(connectionId, remotePath);
    }

    private void requireConnected(String connectionId) {
        if (connectionId == null || connectionId.isBlank()
                || !sshSessionPort.isConnected(connectionId)) {
            throw new IllegalStateException("SSH连接未建立，请先连接");
        }
    }

    private String normalizePath(String path) {
        return path == null || path.isBlank() ? "." : path;
    }
}
