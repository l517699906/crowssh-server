package com.llf.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SftpPermissionRequestDTO {

    private String connectionId;
    private String path;
    private String permissions;
}
