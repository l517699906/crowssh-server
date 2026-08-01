package com.llf.ai.trigger.http;

import com.llf.ai.api.dto.SftpFileDTO;
import com.llf.ai.api.dto.SftpListResponseDTO;
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
import org.springframework.web.bind.annotation.CrossOrigin;
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
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1/ssh/sftp")
@CrossOrigin(origins = "*")
public class SftpController {

    private final ISftpService sftpService;

    public SftpController(ISftpService sftpService) {
        this.sftpService = sftpService;
    }

    @GetMapping("/list")
    public Response<SftpListResponseDTO> list(@RequestParam("connectionId") String connectionId,
                                               @RequestParam(value = "path", required = false) String path) {
        try {
            String resolvedPath = sftpService.resolvePath(connectionId, path);
            List<SftpFileDTO> files = sftpService.list(connectionId, resolvedPath).stream()
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
                                 @RequestParam("file") MultipartFile file) {
        try {
            try (InputStream inputStream = file.getInputStream()) {
                sftpService.upload(connectionId, path, file.getOriginalFilename(),
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
            @RequestParam("path") String path) {
        long size = sftpService.fileSize(connectionId, path);
        String fileName = fileName(path);
        StreamingResponseBody body = outputStream ->
                sftpService.download(connectionId, path, outputStream);

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(size)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(fileName, StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .body(body);
    }

    @GetMapping("/content")
    public ResponseEntity<Response<SftpTextContentDTO>> readText(
            @RequestParam("connectionId") String connectionId,
            @RequestParam("path") String path) {
        try {
            return ResponseEntity.ok(success(toDTO(sftpService.readText(connectionId, path))));
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.warn("读取 SFTP 文本失败 connectionId={} path={} error={}",
                    connectionId, path, e.getMessage());
            return ResponseEntity.badRequest().body(failure(e.getMessage()));
        }
    }

    @PutMapping(value = "/content", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Response<SftpTextContentDTO>> saveText(
            @RequestBody SftpTextSaveRequestDTO request) {
        try {
            SftpTextContentEntity saved = sftpService.saveText(
                    request.getConnectionId(), request.getPath(), request.getContent(),
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
}
