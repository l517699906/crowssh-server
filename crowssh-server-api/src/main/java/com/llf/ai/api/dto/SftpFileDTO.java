package com.llf.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SftpFileDTO {

    private String name;
    private String path;
    private boolean directory;
    private long size;
    private long modifiedAt;
}
