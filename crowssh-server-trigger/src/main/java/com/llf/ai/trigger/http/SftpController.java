package com.llf.ai.trigger.http;

import com.llf.ai.api.dto.SftpFileDTO;
import com.llf.ai.api.dto.SftpListResponseDTO;
import com.llf.ai.api.dto.SftpNamedOperationRequestDTO;
import com.llf.ai.api.dto.SftpPermissionRequestDTO;
import com.llf.ai.api.dto.SftpTextContentDTO;
import com.llf.ai.api.dto.SftpTextSaveRequestDTO;
import com.llf.ai.api.response.Response;
import com.llf.ai.domain.ssh.adapter.port.SftpFileEntity;
import com.llf.ai.domain.ssh.service.ISftpService;
import com.llf.ai.domain.ssh.service.sftp.SftpTextContentEntity;
import com.llf.ai.domain.ssh.service.sftp.SftpVersionConflictException;
import com.llf.ai.types.enums.ResponseCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1/ssh/sftp")
public class SftpController {

    private static final String MISSING_REQUEST_BODY = "请求体不能为空";

    private final ISftpService sftpService;

    public SftpController(ISftpService sftpService) {
        this.sftpService = sftpService;
    }

    @GetMapping("/list")
    public Response<SftpListResponseDTO> list(@RequestParam("connectionId") String connectionId,
                                               @RequestParam(value = "path", required = false) String path,
                                               Principal principal) {
        String ownerId = principal.getName();
        try {
            String resolvedPath = sftpService.resolvePath(ownerId, connectionId, path);
            List<SftpFileDTO> files = sftpService.list(ownerId, connectionId, resolvedPath).stream()
                    .map(this::toDTO)
                    .toList();
            return success(SftpListResponseDTO.builder()
                    .path(resolvedPath)
                    .files(files)
                    .build());
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.warn("读取 SFTP 目录失败 connectionId={} path={} error={}",
                    connectionId, path, e.getMessage());
            return failure(e.getMessage());
        }
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Response<Void> upload(@RequestParam("connectionId") String connectionId,
                                 @RequestParam("path") String path,
                                 @RequestParam("file") MultipartFile file,
                                 Principal principal) {
        try {
            try (InputStream inputStream = file.getInputStream()) {
                sftpService.upload(principal.getName(), connectionId, path, file.getOriginalFilename(),
                        file.getSize(), inputStream);
            }
            return success(null);
        } catch (IOException | IllegalArgumentException | IllegalStateException e) {
            log.warn("SFTP 上传失败 connectionId={} path={} error={}",
                    connectionId, path, e.getMessage());
            return failure(e.getMessage());
        }
    }

    @GetMapping("/download")
    public ResponseEntity<StreamingResponseBody> download(
            @RequestParam("connectionId") String connectionId,
            @RequestParam("path") String path,
            Principal principal) {
        String ownerId = principal.getName();
        long size = sftpService.fileSize(ownerId, connectionId, path);
        String fileName = fileName(path);
        StreamingResponseBody body = outputStream ->
                sftpService.download(ownerId, connectionId, path, outputStream);

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(size)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(fileName, StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .body(body);
    }

    @PostMapping(value = "/rename", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Response<Void> rename(@RequestBody SftpNamedOperationRequestDTO request,
                                 Principal principal) {
        if (request == null) {
            return missingRequestBody("重命名 SFTP 条目");
        }
        return mutate("重命名 SFTP 条目", request.getConnectionId(), request.getPath(),
                () -> sftpService.rename(
                        principal.getName(), request.getConnectionId(),
                        request.getPath(), request.getName()));
    }

    @PostMapping(value = "/directory", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Response<Void> createDirectory(@RequestBody SftpNamedOperationRequestDTO request,
                                          Principal principal) {
        if (request == null) {
            return missingRequestBody("新建 SFTP 文件夹");
        }
        return mutate("新建 SFTP 文件夹", request.getConnectionId(), request.getPath(),
                () -> sftpService.createDirectory(
                        principal.getName(), request.getConnectionId(),
                        request.getPath(), request.getName()));
    }

    @PostMapping(value = "/file", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Response<Void> createFile(@RequestBody SftpNamedOperationRequestDTO request,
                                     Principal principal) {
        if (request == null) {
            return missingRequestBody("新建 SFTP 文件");
        }
        return mutate("新建 SFTP 文件", request.getConnectionId(), request.getPath(),
                () -> sftpService.createFile(
                        principal.getName(), request.getConnectionId(),
                        request.getPath(), request.getName()));
    }

    @PostMapping(value = "/archive", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Response<Void> archive(@RequestBody SftpNamedOperationRequestDTO request,
                                  Principal principal) {
        if (request == null) {
            return missingRequestBody("压缩 SFTP 条目");
        }
        return mutate("压缩 SFTP 条目", request.getConnectionId(), request.getPath(),
                () -> sftpService.archive(
                        principal.getName(), request.getConnectionId(),
                        request.getPath(), request.getName()));
    }

    @PostMapping(value = "/extract", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Response<Void> extract(@RequestBody SftpNamedOperationRequestDTO request,
                                  Principal principal) {
        if (request == null) {
            return missingRequestBody("解压 SFTP 文件");
        }
        return mutate("解压 SFTP 文件", request.getConnectionId(), request.getPath(),
                () -> sftpService.extract(
                        principal.getName(), request.getConnectionId(),
                        request.getPath(), request.getName()));
    }

    @DeleteMapping("/entry")
    public Response<Void> delete(@RequestParam("connectionId") String connectionId,
                                 @RequestParam("path") String path,
                                 Principal principal) {
        return mutate("删除 SFTP 条目", connectionId, path,
                () -> sftpService.delete(principal.getName(), connectionId, path));
    }

    @PutMapping(value = "/permissions", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Response<Void> chmod(@RequestBody SftpPermissionRequestDTO request,
                                Principal principal) {
        if (request == null) {
            return missingRequestBody("修改 SFTP 权限");
        }
        return mutate("修改 SFTP 权限", request.getConnectionId(), request.getPath(),
                () -> sftpService.chmod(
                        principal.getName(), request.getConnectionId(),
                        request.getPath(), request.getPermissions()));
    }

    @GetMapping("/content")
    public ResponseEntity<Response<SftpTextContentDTO>> readText(
            @RequestParam("connectionId") String connectionId,
            @RequestParam("path") String path,
            Principal principal) {
        try {
            return ResponseEntity.ok(success(toDTO(sftpService.readText(
                    principal.getName(), connectionId, path))));
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.warn("读取 SFTP 文本失败 connectionId={} path={} error={}",
                    connectionId, path, e.getMessage());
            return ResponseEntity.badRequest().body(failure(e.getMessage()));
        }
    }

    @PutMapping(value = "/content", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Response<SftpTextContentDTO>> saveText(
            @RequestBody SftpTextSaveRequestDTO request,
            Principal principal) {
        if (request == null) {
            log.warn("保存 SFTP 文本失败 error={}", MISSING_REQUEST_BODY);
            return ResponseEntity.badRequest().body(failure(MISSING_REQUEST_BODY));
        }
        try {
            SftpTextContentEntity saved = sftpService.saveText(
                    principal.getName(), request.getConnectionId(),
                    request.getPath(), request.getContent(),
                    request.getVersion(), request.getEncoding(), request.getLineEnding());
            return ResponseEntity.ok(success(toDTO(saved)));
        } catch (SftpVersionConflictException e) {
            log.info("SFTP 文本保存冲突 connectionId={} path={}",
                    request.getConnectionId(), request.getPath());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(failure(ResponseCode.SFTP_VERSION_CONFLICT, e.getMessage()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.warn("保存 SFTP 文本失败 connectionId={} path={} error={}",
                    request.getConnectionId(), request.getPath(), e.getMessage());
            return ResponseEntity.badRequest().body(failure(e.getMessage()));
        }
    }

    private SftpFileDTO toDTO(SftpFileEntity entity) {
        return SftpFileDTO.builder()
                .name(entity.getName())
                .path(entity.getPath())
                .directory(entity.isDirectory())
                .size(entity.getSize())
                .modifiedAt(entity.getModifiedAt())
                .permissions(entity.getPermissions())
                .build();
    }

    private SftpTextContentDTO toDTO(SftpTextContentEntity entity) {
        return SftpTextContentDTO.builder()
                .path(entity.getPath())
                .content(entity.getContent())
                .version(entity.getVersion())
                .encoding(entity.getEncoding())
                .lineEnding(entity.getLineEnding())
                .size(entity.getSize())
                .modifiedAt(entity.getModifiedAt())
                .build();
    }

    private String fileName(String path) {
        String normalized = path.replace('\\', '/');
        String name = normalized.substring(normalized.lastIndexOf('/') + 1);
        return name.isBlank() ? "download" : name;
    }

    private <T> Response<T> success(T data) {
        return Response.<T>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(data)
                .build();
    }

    private <T> Response<T> failure(String message) {
        return failure(ResponseCode.ILLEGAL_PARAMETER, message);
    }

    private <T> Response<T> failure(ResponseCode code, String message) {
        return Response.<T>builder()
                .code(code.getCode())
                .info(message)
                .build();
    }

    private Response<Void> missingRequestBody(String action) {
        log.warn("{}失败 error={}", action, MISSING_REQUEST_BODY);
        return failure(MISSING_REQUEST_BODY);
    }

    private Response<Void> mutate(String action, String connectionId, String path,
                                  Runnable operation) {
        try {
            operation.run();
            return success(null);
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.warn("{}失败 connectionId={} path={} error={}",
                    action, connectionId, path, e.getMessage());
            return failure(e.getMessage());
        }
    }
}
