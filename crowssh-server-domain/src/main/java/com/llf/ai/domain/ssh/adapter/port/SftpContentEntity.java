package com.llf.ai.domain.ssh.adapter.port;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * SFTP 文件的原始内容及版本信息。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SftpContentEntity {

    private byte[] content;
    private String version;
    private long modifiedAt;
}
