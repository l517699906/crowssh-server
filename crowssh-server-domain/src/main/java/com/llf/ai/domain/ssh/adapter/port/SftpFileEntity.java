package com.llf.ai.domain.ssh.adapter.port;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * SFTP 远程文件元数据。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SftpFileEntity {

    private String name;
    private String path;
    private boolean directory;
    private long size;
    private long modifiedAt;
}
