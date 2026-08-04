package com.llf.ai.domain.ssh.service.sftp;

import com.llf.ai.domain.ssh.adapter.port.ISftpSessionPort;
import com.llf.ai.domain.ssh.adapter.port.ISshSessionPort;
import com.llf.ai.domain.ssh.adapter.port.SftpContentEntity;
import com.llf.ai.domain.ssh.adapter.port.SftpFileEntity;
import com.llf.ai.domain.ssh.service.ISftpService;
import com.llf.ai.domain.ssh.service.ISshConnectionOwnershipService;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

@Service
public class SftpService implements ISftpService {

    private static final long MAX_TEXT_FILE_SIZE = 2L * 1024 * 1024;
    private static final byte[] UTF_8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final byte[] UTF_16_LE_BOM = {(byte) 0xFF, (byte) 0xFE};
    private static final byte[] UTF_16_BE_BOM = {(byte) 0xFE, (byte) 0xFF};

    private final ISshSessionPort sshSessionPort;
    private final ISftpSessionPort sftpSessionPort;
    private final ISshConnectionOwnershipService sshConnectionOwnershipService;

    public SftpService(ISshSessionPort sshSessionPort, ISftpSessionPort sftpSessionPort,
                       ISshConnectionOwnershipService sshConnectionOwnershipService) {
        this.sshSessionPort = sshSessionPort;
        this.sftpSessionPort = sftpSessionPort;
        this.sshConnectionOwnershipService = sshConnectionOwnershipService;
    }

    @Override
    public String resolvePath(String ownerId, String connectionId, String path) {
        requireConnected(ownerId, connectionId);
        return sftpSessionPort.canonicalize(connectionId, normalizePath(path));
    }

    @Override
    public List<SftpFileEntity> list(String ownerId, String connectionId, String path) {
        requireConnected(ownerId, connectionId);
        return sftpSessionPort.list(connectionId, path);
    }

    @Override
    public void upload(String ownerId, String connectionId, String remotePath, String fileName,
                       long size, InputStream inputStream) {
        requireConnected(ownerId, connectionId);
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("上传文件名不能为空");
        }
        sftpSessionPort.upload(connectionId, remotePath, fileName, size, inputStream);
    }

    @Override
    public void download(String ownerId, String connectionId, String remotePath,
                         OutputStream outputStream) {
        requireConnected(ownerId, connectionId);
        sftpSessionPort.download(connectionId, remotePath, outputStream);
    }

    @Override
    public long fileSize(String ownerId, String connectionId, String remotePath) {
        requireConnected(ownerId, connectionId);
        return sftpSessionPort.fileSize(connectionId, remotePath);
    }

    @Override
    public void rename(String ownerId, String connectionId, String remotePath, String newName) {
        requireConnected(ownerId, connectionId);
        requireMutationPath(remotePath);
        sftpSessionPort.rename(connectionId, remotePath, requireEntryName(newName, "新名称"));
    }

    @Override
    public void createDirectory(String ownerId, String connectionId, String remotePath, String name) {
        requireConnected(ownerId, connectionId);
        requireMutationPath(remotePath);
        sftpSessionPort.createDirectory(
                connectionId, remotePath, requireEntryName(name, "文件夹名称"));
    }

    @Override
    public void createFile(String ownerId, String connectionId, String remotePath, String name) {
        requireConnected(ownerId, connectionId);
        requireMutationPath(remotePath);
        sftpSessionPort.createFile(connectionId, remotePath, requireEntryName(name, "文件名称"));
    }

    @Override
    public void archive(String ownerId, String connectionId, String remotePath, String archiveName) {
        requireConnected(ownerId, connectionId);
        requireMutationPath(remotePath);
        String validatedName = requireEntryName(archiveName, "压缩包名称");
        if (!validatedName.toLowerCase(Locale.ROOT).endsWith(".tar.gz")) {
            throw new IllegalArgumentException("压缩包名称必须以 .tar.gz 结尾");
        }
        sftpSessionPort.archive(connectionId, remotePath, validatedName);
    }

    @Override
    public void extract(String ownerId, String connectionId, String remotePath, String directoryName) {
        requireConnected(ownerId, connectionId);
        requireMutationPath(remotePath);
        sftpSessionPort.extract(
                connectionId, remotePath, requireEntryName(directoryName, "目标文件夹名称"));
    }

    @Override
    public void delete(String ownerId, String connectionId, String remotePath) {
        requireConnected(ownerId, connectionId);
        requireMutationPath(remotePath);
        String normalized = remotePath.replace('\\', '/').replaceAll("/+$", "");
        if (normalized.isBlank() || "/".equals(normalized)
                || ".".equals(normalized) || "..".equals(normalized)) {
            throw new IllegalArgumentException("禁止删除远程根目录");
        }
        sftpSessionPort.delete(connectionId, remotePath);
    }

    @Override
    public void chmod(String ownerId, String connectionId, String remotePath, String permissions) {
        requireConnected(ownerId, connectionId);
        requireMutationPath(remotePath);
        if (permissions == null || !permissions.matches("[0-7]{3,4}")) {
            throw new IllegalArgumentException("权限必须是 3 或 4 位八进制数字");
        }
        sftpSessionPort.chmod(connectionId, remotePath, Integer.parseInt(permissions, 8));
    }

    @Override
    public SftpTextContentEntity readText(String ownerId, String connectionId, String remotePath) {
        requireConnected(ownerId, connectionId);
        requireRemotePath(remotePath);
        SftpContentEntity source = sftpSessionPort.readContent(
                connectionId, remotePath, MAX_TEXT_FILE_SIZE);
        DecodedText decoded = decodeText(source.getContent());
        return SftpTextContentEntity.builder()
                .path(remotePath)
                .content(decoded.content())
                .version(source.getVersion())
                .encoding(decoded.encoding())
                .lineEnding(decoded.lineEnding())
                .size(source.getContent().length)
                .modifiedAt(source.getModifiedAt())
                .build();
    }

    @Override
    public SftpTextContentEntity saveText(String ownerId, String connectionId, String remotePath,
                                          String content,
                                          String expectedVersion, String encoding,
                                          String lineEnding) {
        requireConnected(ownerId, connectionId);
        requireRemotePath(remotePath);
        if (content == null) {
            throw new IllegalArgumentException("文件内容不能为空");
        }
        if (expectedVersion == null || expectedVersion.isBlank()) {
            throw new IllegalArgumentException("文件版本不能为空");
        }

        String normalizedContent = normalizeLineEndings(content);
        byte[] encoded = encodeText(normalizedContent, encoding, lineEnding);
        if (encoded.length > MAX_TEXT_FILE_SIZE) {
            throw new IllegalArgumentException("文本文件不能超过 2 MB");
        }

        SftpContentEntity saved = sftpSessionPort.writeContent(
                connectionId, remotePath, encoded, expectedVersion, MAX_TEXT_FILE_SIZE);
        return SftpTextContentEntity.builder()
                .path(remotePath)
                .content(normalizedContent)
                .version(saved.getVersion())
                .encoding(encoding)
                .lineEnding(lineEnding)
                .size(encoded.length)
                .modifiedAt(saved.getModifiedAt())
                .build();
    }

    private void requireConnected(String ownerId, String connectionId) {
        sshConnectionOwnershipService.requireOwnership(ownerId, connectionId);
        if (!sshSessionPort.isConnected(connectionId)) {
            throw new IllegalStateException("SSH连接未建立，请先连接");
        }
    }

    private String normalizePath(String path) {
        return path == null || path.isBlank() ? "." : path;
    }

    private void requireRemotePath(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("远程文件路径不能为空");
        }
    }

    private void requireMutationPath(String path) {
        requireRemotePath(path);
        String normalized = path.replace('\\', '/');
        if (normalized.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("远程文件路径无效");
        }
        for (String segment : normalized.split("/")) {
            if (".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException("远程文件路径不能包含父级或当前目录段");
            }
        }
    }

    private String requireEntryName(String name, String label) {
        String normalized = name == null ? "" : name.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        if (".".equals(normalized) || "..".equals(normalized)
                || normalized.indexOf('/') >= 0 || normalized.indexOf('\\') >= 0
                || normalized.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(label + "不能包含路径分隔符");
        }
        return normalized;
    }

    private DecodedText decodeText(byte[] bytes) {
        Charset charset = StandardCharsets.UTF_8;
        String encoding = "UTF-8";
        int offset = 0;
        if (startsWith(bytes, UTF_8_BOM)) {
            encoding = "UTF-8-BOM";
            offset = UTF_8_BOM.length;
        } else if (startsWith(bytes, UTF_16_LE_BOM)) {
            charset = StandardCharsets.UTF_16LE;
            encoding = "UTF-16LE";
            offset = UTF_16_LE_BOM.length;
        } else if (startsWith(bytes, UTF_16_BE_BOM)) {
            charset = StandardCharsets.UTF_16BE;
            encoding = "UTF-16BE";
            offset = UTF_16_BE_BOM.length;
        }

        try {
            CharBuffer decoded = charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset));
            String content = decoded.toString();
            if (content.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("该文件包含二进制内容，无法使用文本编辑器打开");
            }
            String lineEnding = detectLineEnding(content);
            return new DecodedText(normalizeLineEndings(content), encoding, lineEnding);
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("文件不是受支持的 UTF-8 或 UTF-16 文本", e);
        }
    }

    private byte[] encodeText(String content, String encoding, String lineEnding) {
        String output = switch (lineEnding) {
            case "LF" -> content;
            case "CRLF" -> content.replace("\n", "\r\n");
            case "CR" -> content.replace("\n", "\r");
            default -> throw new IllegalArgumentException("不支持的换行格式: " + lineEnding);
        };

        Charset charset;
        byte[] bom;
        switch (encoding) {
            case "UTF-8" -> {
                charset = StandardCharsets.UTF_8;
                bom = new byte[0];
            }
            case "UTF-8-BOM" -> {
                charset = StandardCharsets.UTF_8;
                bom = UTF_8_BOM;
            }
            case "UTF-16LE" -> {
                charset = StandardCharsets.UTF_16LE;
                bom = UTF_16_LE_BOM;
            }
            case "UTF-16BE" -> {
                charset = StandardCharsets.UTF_16BE;
                bom = UTF_16_BE_BOM;
            }
            default -> throw new IllegalArgumentException("不支持的文本编码: " + encoding);
        }

        try {
            ByteBuffer encoded = charset.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(output));
            byte[] result = Arrays.copyOf(bom, bom.length + encoded.remaining());
            encoded.get(result, bom.length, encoded.remaining());
            return result;
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("文件内容无法使用原编码保存", e);
        }
    }

    private boolean startsWith(byte[] source, byte[] prefix) {
        if (source.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (source[i] != prefix[i]) return false;
        }
        return true;
    }

    private String detectLineEnding(String content) {
        int crlf = 0;
        int lf = 0;
        int cr = 0;
        for (int i = 0; i < content.length(); i++) {
            char current = content.charAt(i);
            if (current == '\r') {
                if (i + 1 < content.length() && content.charAt(i + 1) == '\n') {
                    crlf++;
                    i++;
                } else {
                    cr++;
                }
            } else if (current == '\n') {
                lf++;
            }
        }
        if (crlf >= lf && crlf >= cr && crlf > 0) return "CRLF";
        if (cr > lf && cr > 0) return "CR";
        return "LF";
    }

    private String normalizeLineEndings(String content) {
        return content.replace("\r\n", "\n").replace('\r', '\n');
    }

    private record DecodedText(String content, String encoding, String lineEnding) { }
}
