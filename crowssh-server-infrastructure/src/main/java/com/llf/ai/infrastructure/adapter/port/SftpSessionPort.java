package com.llf.ai.infrastructure.adapter.port;

import com.llf.ai.domain.ssh.adapter.port.ISftpSessionPort;
import com.llf.ai.domain.ssh.adapter.port.SftpContentEntity;
import com.llf.ai.domain.ssh.adapter.port.SftpFileEntity;
import com.llf.ai.domain.ssh.service.sftp.SftpVersionConflictException;
import lombok.extern.slf4j.Slf4j;
import net.schmizz.sshj.sftp.FileAttributes;
import net.schmizz.sshj.sftp.FileMode;
import net.schmizz.sshj.sftp.RenameFlags;
import net.schmizz.sshj.sftp.RemoteResourceInfo;
import net.schmizz.sshj.sftp.SFTPClient;
import net.schmizz.sshj.xfer.LocalDestFile;
import net.schmizz.sshj.xfer.LocalFileFilter;
import net.schmizz.sshj.xfer.LocalSourceFile;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Slf4j
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

    @Override
    public SftpContentEntity readContent(String connectionId, String remotePath, long maxSize) {
        try (SFTPClient client = sshSessionPort.openSftpClient(connectionId)) {
            return readContent(client, remotePath, maxSize);
        } catch (ContentTooLargeException e) {
            throw new IllegalArgumentException("文本文件不能超过 2 MB", e);
        } catch (IOException e) {
            throw transferError("读取文件内容失败", e);
        }
    }

    @Override
    public SftpContentEntity writeContent(String connectionId, String remotePath, byte[] content,
                                          String expectedVersion, long maxSize) {
        if (content.length > maxSize) {
            throw new IllegalArgumentException("文本文件不能超过 2 MB");
        }
        String temporaryPath = temporaryPath(remotePath);
        try (SFTPClient client = sshSessionPort.openSftpClient(connectionId)) {
            FileAttributes originalAttributes = requireEditableFile(client, remotePath, maxSize);
            SftpContentEntity original = downloadContent(
                    client, remotePath, originalAttributes, maxSize);
            requireVersion(expectedVersion, original.getVersion());

            boolean temporaryFileCreated = false;
            try {
                client.put(new StreamSourceFile(
                        fileName(temporaryPath), content.length,
                        new java.io.ByteArrayInputStream(content)), temporaryPath);
                temporaryFileCreated = true;
                client.chmod(temporaryPath, originalAttributes.getMode().getPermissionsMask());

                // 上传临时文件期间远端仍可能被外部程序修改，替换前再检查一次。
                SftpContentEntity latest = readContent(client, remotePath, maxSize);
                requireVersion(expectedVersion, latest.getVersion());
                client.rename(temporaryPath, remotePath,
                        EnumSet.of(RenameFlags.OVERWRITE, RenameFlags.ATOMIC));
                temporaryFileCreated = false;

                FileAttributes savedAttributes = client.stat(remotePath);
                return SftpContentEntity.builder()
                        .content(content)
                        .version(version(content))
                        .modifiedAt(savedAttributes.getMtime())
                        .build();
            } finally {
                if (temporaryFileCreated) {
                    removeTemporaryFile(client, temporaryPath);
                }
            }
        } catch (ContentTooLargeException e) {
            throw new SftpVersionConflictException("远程文件已发生变化，请重新加载后再保存");
        } catch (IOException e) {
            throw transferError("保存文件失败", e);
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

    private SftpContentEntity readContent(SFTPClient client, String remotePath, long maxSize)
            throws IOException {
        FileAttributes before = requireEditableFile(client, remotePath, maxSize);
        SftpContentEntity content = downloadContent(client, remotePath, before, maxSize);
        FileAttributes after = requireEditableFile(client, remotePath, maxSize);
        if (before.getSize() != after.getSize() || before.getMtime() != after.getMtime()) {
            throw new IllegalStateException("读取期间远程文件已发生变化，请重新打开");
        }
        content.setModifiedAt(after.getMtime());
        return content;
    }

    private SftpContentEntity downloadContent(SFTPClient client, String remotePath,
                                               FileAttributes attributes, long maxSize)
            throws IOException {
        BoundedOutputStream outputStream = new BoundedOutputStream(maxSize);
        client.get(remotePath, new StreamDestFile(outputStream));
        byte[] content = outputStream.toByteArray();
        return SftpContentEntity.builder()
                .content(content)
                .version(version(content))
                .modifiedAt(attributes.getMtime())
                .build();
    }

    private FileAttributes requireEditableFile(SFTPClient client, String remotePath, long maxSize)
            throws IOException {
        FileAttributes attributes = client.lstat(remotePath);
        FileMode.Type type = attributes.getType();
        if (type == FileMode.Type.DIRECTORY) {
            throw new IllegalArgumentException("目录不能使用文本编辑器打开");
        }
        if (type == FileMode.Type.SYMLINK) {
            throw new IllegalArgumentException("暂不支持通过文本编辑器编辑符号链接");
        }
        if (attributes.getSize() > maxSize) {
            throw new ContentTooLargeException();
        }
        return attributes;
    }

    private void requireVersion(String expectedVersion, String actualVersion) {
        boolean matches = MessageDigest.isEqual(
                expectedVersion.getBytes(StandardCharsets.UTF_8),
                actualVersion.getBytes(StandardCharsets.UTF_8));
        if (!matches) {
            throw new SftpVersionConflictException("远程文件已被其他程序修改，请重新加载后再保存");
        }
    }

    private String version(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前运行环境不支持 SHA-256", e);
        }
    }

    private String temporaryPath(String remotePath) {
        String normalized = remotePath.replace('\\', '/');
        int separator = normalized.lastIndexOf('/');
        String directory = separator >= 0 ? normalized.substring(0, separator + 1) : "";
        String name = separator >= 0 ? normalized.substring(separator + 1) : normalized;
        if (name.isBlank()) {
            throw new IllegalArgumentException("远程文件路径无效");
        }
        return directory + "." + name + ".crowssh-" + UUID.randomUUID() + ".tmp";
    }

    private String fileName(String path) {
        int separator = path.lastIndexOf('/');
        return separator >= 0 ? path.substring(separator + 1) : path;
    }

    private void removeTemporaryFile(SFTPClient client, String temporaryPath) {
        try {
            client.rm(temporaryPath);
        } catch (IOException cleanupError) {
            log.warn("清理 SFTP 临时文件失败 path={} error={}",
                    temporaryPath, cleanupError.getMessage());
        }
    }

    private IllegalStateException transferError(String action, IOException cause) {
        return new IllegalStateException(action + ": " + cause.getMessage(), cause);
    }

    private static final class ContentTooLargeException extends IOException { }

    private static final class BoundedOutputStream extends OutputStream {
        private final long maxSize;
        private final ByteArrayOutputStream delegate = new ByteArrayOutputStream();
        private long written;

        private BoundedOutputStream(long maxSize) {
            this.maxSize = maxSize;
        }

        @Override
        public void write(int value) throws IOException {
            requireCapacity(1);
            delegate.write(value);
            written++;
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            requireCapacity(length);
            delegate.write(bytes, offset, length);
            written += length;
        }

        private void requireCapacity(int additionalBytes) throws ContentTooLargeException {
            if (additionalBytes < 0 || written + additionalBytes > maxSize) {
                throw new ContentTooLargeException();
            }
        }

        private byte[] toByteArray() {
            return delegate.toByteArray();
        }
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
