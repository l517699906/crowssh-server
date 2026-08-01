package com.llf.ai.domain.ssh.service.sftp;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 供远程文本编辑器使用的文本内容。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SftpTextContentEntity {

    private String path;
    private String content;
    private String version;
    private String encoding;
    private String lineEnding;
    private long size;
    private long modifiedAt;
}
