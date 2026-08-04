package com.llf.ai.infrastructure.adapter.port;

import com.llf.ai.domain.ssh.adapter.port.ISftpSessionPort;
import com.llf.ai.domain.ssh.adapter.port.SftpContentEntity;
import com.llf.ai.domain.ssh.adapter.port.SftpFileEntity;
import com.llf.ai.domain.ssh.service.sftp.SftpVersionConflictException;
import lombok.extern.slf4j.Slf4j;
import net.schmizz.sshj.connection.channel.direct.Session;
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
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class SftpSessionPort implements ISftpSessionPort {

    private static final long ARCHIVE_COMMAND_TIMEOUT_MS = 5L * 60 * 1000;
    private static final int MAX_COMMAND_OUTPUT_BYTES = 16 * 1024;
    private static final int MAX_DELETE_DEPTH = 128;
    private static final String SAFE_EXTRACT_SCRIPT = """
            import bz2
            import gzip
            import lzma
            import os
            import shutil
            import stat
            import sys
            import tarfile
            import zipfile

            archive = os.path.realpath(sys.argv[1])
            target = os.path.realpath(sys.argv[2])

            def destination(name):
                if "\\x00" in name:
                    raise ValueError("Archive member contains NUL")
                normalized = os.path.normpath(name.replace("\\\\", "/"))
                if normalized == ".." or normalized.startswith("../") or os.path.isabs(normalized):
                    raise ValueError("Archive member escapes target directory: " + name)
                resolved = os.path.realpath(os.path.join(target, normalized))
                if os.path.commonpath([target, resolved]) != target:
                    raise ValueError("Archive member escapes target directory: " + name)
                return resolved

            def write_stream(source, output, mode=0):
                os.makedirs(os.path.dirname(output), exist_ok=True)
                with source, open(output, "xb") as destination_file:
                    shutil.copyfileobj(source, destination_file)
                if mode:
                    os.chmod(output, mode & 0o777)

            try:
                os.mkdir(target)
                if zipfile.is_zipfile(archive):
                    with zipfile.ZipFile(archive) as source_archive:
                        for member in source_archive.infolist():
                            mode = (member.external_attr >> 16) & 0xFFFF
                            if stat.S_ISLNK(mode):
                                raise ValueError("Archive links are not supported: " + member.filename)
                            output = destination(member.filename)
                            if member.is_dir():
                                os.makedirs(output, exist_ok=True)
                            else:
                                write_stream(source_archive.open(member), output, mode)
                elif tarfile.is_tarfile(archive):
                    with tarfile.open(archive, "r:*") as source_archive:
                        for member in source_archive.getmembers():
                            if not member.isdir() and not member.isfile():
                                raise ValueError("Archive links and special files are not supported: " + member.name)
                            output = destination(member.name)
                            if member.isdir():
                                os.makedirs(output, exist_ok=True)
                            else:
                                source = source_archive.extractfile(member)
                                if source is None:
                                    raise ValueError("Cannot read archive member: " + member.name)
                                write_stream(source, output, member.mode)
                else:
                    lower_name = archive.lower()
                    formats = ((".gz", gzip.open), (".bz2", bz2.open), (".xz", lzma.open))
                    match = next(((suffix, opener) for suffix, opener in formats
                                  if lower_name.endswith(suffix)), None)
                    if match is None:
                        raise ValueError("Unsupported archive format")
                    suffix, opener = match
                    output_name = os.path.basename(archive)[:-len(suffix)] or "extracted"
                    write_stream(opener(archive, "rb"), destination(output_name))
            except BaseException:
                shutil.rmtree(target, ignore_errors=True)
                raise
            """;

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
    public void rename(String connectionId, String remotePath, String newName) {
        String targetPath = siblingPath(remotePath, newName);
        if (targetPath.equals(remotePath)) {
            return;
        }
        try (SFTPClient client = sshSessionPort.openSftpClient(connectionId)) {
            requireAvailable(client, targetPath);
            client.rename(remotePath, targetPath);
        } catch (IOException e) {
            throw transferError("重命名失败", e);
        }
    }

    @Override
    public void createDirectory(String connectionId, String remotePath, String name) {
        String targetPath = join(remotePath, name);
        try (SFTPClient client = sshSessionPort.openSftpClient(connectionId)) {
            requireAvailable(client, targetPath);
            client.mkdir(targetPath);
        } catch (IOException e) {
            throw transferError("新建文件夹失败", e);
        }
    }

    @Override
    public void createFile(String connectionId, String remotePath, String name) {
        String targetPath = join(remotePath, name);
        try (SFTPClient client = sshSessionPort.openSftpClient(connectionId)) {
            requireAvailable(client, targetPath);
            client.put(new StreamSourceFile(
                    name, 0, new java.io.ByteArrayInputStream(new byte[0])), targetPath);
        } catch (IOException e) {
            throw transferError("新建文件失败", e);
        }
    }

    @Override
    public void archive(String connectionId, String remotePath, String archiveName) {
        String parent = parentPath(remotePath);
        String targetPath = join(parent, archiveName);
        String temporaryPath = join(parent, ".crowssh-archive-" + UUID.randomUUID() + ".tmp");
        requireTargetAvailable(connectionId, targetPath, "压缩包已存在");
        try {
            executeRemoteCommand(
                    connectionId, archiveCommand(remotePath, temporaryPath), "压缩远程条目");
            publishTemporaryEntry(connectionId, temporaryPath, targetPath);
        } catch (RuntimeException e) {
            removeEntryQuietly(connectionId, temporaryPath);
            throw e;
        }
    }

    @Override
    public void extract(String connectionId, String remotePath, String directoryName) {
        String parent = parentPath(remotePath);
        String targetPath = join(parent, directoryName);
        String temporaryPath = join(parent, ".crowssh-extract-" + UUID.randomUUID() + ".tmp");
        requireTargetAvailable(connectionId, targetPath, "目标文件夹已存在");
        try {
            executeRemoteCommand(
                    connectionId, extractCommand(remotePath, temporaryPath), "解压远程文件");
            publishTemporaryEntry(connectionId, temporaryPath, targetPath);
        } catch (RuntimeException e) {
            removeEntryQuietly(connectionId, temporaryPath);
            throw e;
        }
    }

    @Override
    public void delete(String connectionId, String remotePath) {
        try (SFTPClient client = sshSessionPort.openSftpClient(connectionId)) {
            deleteEntry(client, canonicalMutationPath(client, remotePath), 0);
        } catch (IOException e) {
            throw transferError("删除远程条目失败", e);
        }
    }

    @Override
    public void chmod(String connectionId, String remotePath, int permissions) {
        try (SFTPClient client = sshSessionPort.openSftpClient(connectionId)) {
            client.chmod(remotePath, permissions);
        } catch (IOException e) {
            throw transferError("修改远程权限失败", e);
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
                .permissions(formatPermissions(attributes))
                .build();
    }

    private void requireAvailable(SFTPClient client, String targetPath) throws IOException {
        if (client.statExistence(targetPath) != null) {
            throw new IllegalArgumentException("同名远程条目已存在");
        }
    }

    private void deleteEntry(SFTPClient client, String path, int depth) throws IOException {
        if (depth > MAX_DELETE_DEPTH) {
            throw new IOException("远程目录层级过深，已停止删除");
        }
        FileAttributes attributes = client.lstat(path);
        if (attributes.getType() != FileMode.Type.DIRECTORY) {
            client.rm(path);
            return;
        }

        for (RemoteResourceInfo child : client.ls(path)) {
            if (".".equals(child.getName()) || "..".equals(child.getName())) {
                continue;
            }
            deleteEntry(client, child.getPath(), depth + 1);
        }
        client.rmdir(path);
    }

    private String formatPermissions(FileAttributes attributes) {
        int permissions = attributes.getMode().getPermissionsMask() & 07777;
        return permissions > 0777
                ? String.format(Locale.ROOT, "%04o", permissions)
                : String.format(Locale.ROOT, "%03o", permissions);
    }

    private String archiveCommand(String remotePath, String targetPath) {
        String parent = parentPath(remotePath);
        return "command -v tar >/dev/null 2>&1"
                + " || { printf 'Remote command tar is unavailable\\n' >&2; exit 127; }; "
                + "tar -czf " + shellQuote(targetPath)
                + " -C " + shellQuote(parent)
                + " -- " + shellQuote(fileName(remotePath));
    }

    private String extractCommand(String remotePath, String targetPath) {
        return "command -v python3 >/dev/null 2>&1"
                + " || { printf 'Remote command python3 is unavailable\\n' >&2; exit 127; }; "
                + "python3 -c " + shellQuote(SAFE_EXTRACT_SCRIPT)
                + " " + shellQuote(remotePath)
                + " " + shellQuote(targetPath);
    }

    private void requireTargetAvailable(String connectionId, String targetPath, String message) {
        try (SFTPClient client = sshSessionPort.openSftpClient(connectionId)) {
            if (client.statExistence(targetPath) != null) {
                throw new IllegalArgumentException(message);
            }
        } catch (IOException e) {
            throw transferError("检查远程目标失败", e);
        }
    }

    private void publishTemporaryEntry(
            String connectionId, String temporaryPath, String targetPath) {
        try (SFTPClient client = sshSessionPort.openSftpClient(connectionId)) {
            requireAvailable(client, targetPath);
            client.rename(temporaryPath, targetPath);
        } catch (IOException e) {
            throw transferError("发布远程操作结果失败", e);
        }
    }

    private void removeEntryQuietly(String connectionId, String path) {
        try (SFTPClient client = sshSessionPort.openSftpClient(connectionId)) {
            if (client.statExistence(path) != null) {
                deleteEntry(client, path, 0);
            }
        } catch (IOException | RuntimeException e) {
            log.warn("清理 SFTP 操作临时条目失败 path={} error={}", path, e.getMessage());
        }
    }

    private String canonicalMutationPath(SFTPClient client, String path) throws IOException {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("远程文件路径无效");
        }
        for (String segment : path.replace('\\', '/').split("/")) {
            if (".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException("远程文件路径不能包含 . 或 ..");
            }
        }
        String name = fileName(path);
        if (name.isBlank() || ".".equals(name) || "..".equals(name)) {
            throw new IllegalArgumentException("远程文件路径无效");
        }
        return join(client.canonicalize(parentPath(path)), name);
    }

    private void executeRemoteCommand(String connectionId, String commandText, String action) {
        Session session = null;
        Session.Command command = null;
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        Thread stdoutReader = null;
        Thread stderrReader = null;
        try {
            session = sshSessionPort.openSession(connectionId);
            command = session.exec(commandText);
            stdoutReader = startOutputReader(command.getInputStream(), stdout, action + " stdout");
            stderrReader = startOutputReader(command.getErrorStream(), stderr, action + " stderr");
            command.join(ARCHIVE_COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (command.isOpen()) {
                closeQuietly(command);
                joinReader(stdoutReader);
                joinReader(stderrReader);
                throw new RemoteCommandTimeoutException(
                        action + "超时，临时结果未发布，请稍后刷新目录确认");
            }

            closeQuietly(command);
            joinReader(stdoutReader);
            joinReader(stderrReader);
            Integer exitStatus = command.getExitStatus();
            if (exitStatus == null || exitStatus != 0) {
                String output = commandOutput(stderr, stdout);
                throw new IllegalStateException(action + "失败"
                        + (output.isBlank() ? "" : ": " + output));
            }
        } catch (IOException e) {
            throw transferError(action + "失败", e);
        } finally {
            closeQuietly(command);
            closeQuietly(session);
        }
    }

    private Thread startOutputReader(InputStream input, ByteArrayOutputStream output, String name) {
        Thread reader = new Thread(() -> {
            byte[] bytes = new byte[4096];
            try {
                int length;
                while ((length = input.read(bytes)) >= 0) {
                    synchronized (output) {
                        int remaining = MAX_COMMAND_OUTPUT_BYTES - output.size();
                        if (remaining > 0) {
                            output.write(bytes, 0, Math.min(length, remaining));
                        }
                    }
                }
            } catch (IOException e) {
                log.debug("读取{}失败 reason={}", name, e.getMessage());
            }
        }, "sftp-operation-" + name.replace(' ', '-'));
        reader.setDaemon(true);
        reader.start();
        return reader;
    }

    private void joinReader(Thread reader) {
        if (reader == null) {
            return;
        }
        try {
            reader.join(2_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String commandOutput(ByteArrayOutputStream primary, ByteArrayOutputStream secondary) {
        String first;
        String second;
        synchronized (primary) {
            first = primary.toString(StandardCharsets.UTF_8);
        }
        synchronized (secondary) {
            second = secondary.toString(StandardCharsets.UTF_8);
        }
        String output = first.isBlank() ? second : first;
        return output.replaceAll("[\\r\\n]+", " ").trim();
    }

    private void closeQuietly(java.io.Closeable resource) {
        if (resource == null) {
            return;
        }
        try {
            resource.close();
        } catch (IOException e) {
            log.debug("关闭 SFTP 操作通道失败 reason={}", e.getMessage());
        }
    }

    private String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private String siblingPath(String remotePath, String name) {
        return join(parentPath(remotePath), name);
    }

    private String parentPath(String path) {
        String normalized = path.replace('\\', '/').replaceAll("/+$", "");
        int separator = normalized.lastIndexOf('/');
        if (separator < 0) {
            return ".";
        }
        return separator == 0 ? "/" : normalized.substring(0, separator);
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
        String normalized = path.replace('\\', '/').replaceAll("/+$", "");
        int separator = normalized.lastIndexOf('/');
        return separator >= 0 ? normalized.substring(separator + 1) : normalized;
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

    private static final class RemoteCommandTimeoutException extends IllegalStateException {
        private RemoteCommandTimeoutException(String message) {
            super(message);
        }
    }

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
