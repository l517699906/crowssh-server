package com.llf.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SftpTextContentDTO {

    private String path;
    private String content;
    private String version;
    private String encoding;
    private String lineEnding;
    private long size;
    private long modifiedAt;
}
