package com.llf.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SftpNamedOperationRequestDTO {

    private String connectionId;
    private String path;
    private String name;
}
