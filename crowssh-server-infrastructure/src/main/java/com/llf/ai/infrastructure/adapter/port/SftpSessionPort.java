package com.llf.ai.infrastructure.adapter.port;

import com.llf.ai.domain.ssh.adapter.port.ISftpSessionPort;
import com.llf.ai.domain.ssh.adapter.port.SftpFileEntity;
import net.schmizz.sshj.sftp.FileAttributes;
import net.schmizz.sshj.sftp.RemoteResourceInfo;
import net.schmizz.sshj.sftp.SFTPClient;
import net.schmizz.sshj.xfer.LocalDestFile;
import net.schmizz.sshj.xfer.LocalFileFilter;
import net.schmizz.sshj.xfer.LocalSourceFile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.List;

@Component
public class SftpSessionPort implements ISftpSessionPort {

    private final SshSessionPort sshSessionPort;

    public SftpSessionPort(SshSessionPort sshSessionPort) {
        this.sshSessionPort = sshSessionPort;
    }

    @Override
    public String canonicalize(String connectionId, String path) {
        try (SFTPClient client = sshSessionPort.openSftpClient(connectionId)) {
            return client.canonicalize(path);
        } catch (IOException e) {
            throw transferError("解析远程路径失败", e);
        }
    }

    @Override
    public List<SftpFileEntity> list(String connectionId, String path) {
        try (SFTPClient client = sshSessionPort.openSftpClient(connectionId)) {
            return client.ls(path).stream()
                    .filter(resource -> !".".equals(resource.getName())
                            && !"..".equals(resource.getName()))
                    .map(this::toEntity)
                    .sorted((left, right) -> {
                        if (left.isDirectory() != right.isDirectory()) {
                            return left.isDirectory() ? -1 : 1;
                        }
                        return left.getName().compareToIgnoreCase(right.getName());
                    })
                    .toList();
        } catch (IOException e) {
            throw transferError("读取远程目录失败", e);
        }
    }

    @Override
    public void upload(String connectionId, String remotePath, String fileName,
                       long size, InputStream inputStream) {
        String safeName = fileName.replace('\\', '/');
        safeName = safeName.substring(safeName.lastIndexOf('/') + 1);
        if (safeName.isBlank()) {
            throw new IllegalArgumentException("上传文件名不能为空");
        }
        String target = join(remotePath, safeName);
        try (SFTPClient client = sshSessionPort.openSftpClient(connectionId)) {
            client.put(new StreamSourceFile(safeName, size, inputStream), target);
        } catch (IOException e) {
            throw transferError("上传文件失败", e);
        }
    }

    @Override
    public void download(String connectionId, String remotePath, OutputStream outputStream) {
        try (SFTPClient client = sshSessionPort.openSftpClient(connectionId)) {
            client.get(remotePath, new StreamDestFile(outputStream));
        } catch (IOException e) {
            throw transferError("下载文件失败", e);
        }
    }

    @Override
    public long fileSize(String connectionId, String remotePath) {
        try (SFTPClient client = sshSessionPort.openSftpClient(connectionId)) {
            return client.size(remotePath);
        } catch (IOException e) {
            throw transferError("读取文件信息失败", e);
        }
    }

    private SftpFileEntity toEntity(RemoteResourceInfo resource) {
        FileAttributes attributes = resource.getAttributes();
        return SftpFileEntity.builder()
                .name(resource.getName())
                .path(resource.getPath())
                .directory(resource.isDirectory())
                .size(attributes.getSize())
                .modifiedAt(attributes.getMtime())
                .build();
    }

    private String join(String directory, String name) {
        if (directory == null || directory.isBlank()) {
            throw new IllegalArgumentException("上传目录不能为空");
        }
        if (directory.endsWith("/")) {
            return directory + name;
        }
        return directory + "/" + name;
    }

    private IllegalStateException transferError(String action, IOException cause) {
        return new IllegalStateException(action + ": " + cause.getMessage(), cause);
    }

    private static final class StreamSourceFile implements LocalSourceFile {
        private final String name;
        private final long size;
        private final InputStream inputStream;

        private StreamSourceFile(String name, long size, InputStream inputStream) {
            this.name = name;
            this.size = size;
            this.inputStream = inputStream;
        }

        @Override public String getName() { return name; }
        @Override public long getLength() { return size; }
        @Override public InputStream getInputStream() { return inputStream; }
        @Override public int getPermissions() { return 0644; }
        @Override public boolean isFile() { return true; }
        @Override public boolean isDirectory() { return false; }
        @Override public Iterable<? extends LocalSourceFile> getChildren(LocalFileFilter filter) {
            return Collections.emptyList();
        }
        @Override public boolean providesAtimeMtime() { return false; }
        @Override public long getLastAccessTime() { return 0; }
        @Override public long getLastModifiedTime() { return 0; }
    }

    private static final class StreamDestFile implements LocalDestFile {
        private final OutputStream outputStream;

        private StreamDestFile(OutputStream outputStream) {
            this.outputStream = outputStream;
        }

        @Override public long getLength() { return 0; }
        @Override public OutputStream getOutputStream() { return outputStream; }
        @Override public OutputStream getOutputStream(boolean append) { return outputStream; }
        @Override public LocalDestFile getChild(String name) { return this; }
        @Override public LocalDestFile getTargetFile(String filename) { return this; }
        @Override public LocalDestFile getTargetDirectory(String dirname) { return this; }
        @Override public void setPermissions(int perms) { }
        @Override public void setLastAccessedTime(long time) { }
        @Override public void setLastModifiedTime(long time) { }
    }
}
