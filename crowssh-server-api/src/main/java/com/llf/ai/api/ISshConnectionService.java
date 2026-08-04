package com.llf.ai.api;

import com.llf.ai.api.dto.SshConnectionRequestDTO;
import com.llf.ai.api.dto.SshConnectionResponseDTO;
import com.llf.ai.api.response.Response;

import java.util.List;

/**
 * SSH连接服务接口
 *
 * @author llf
 */
public interface ISshConnectionService {

    /**
     * 创建SSH连接
     */
    Response<SshConnectionResponseDTO> createConnection(SshConnectionRequestDTO requestDTO);

    /**
     * 更新SSH连接
     */
    Response<SshConnectionResponseDTO> updateConnection(SshConnectionRequestDTO requestDTO);

    /**
     * 删除SSH连接
     */
    Response<Void> deleteConnection(String connectionId);

    /**
     * 查询单个连接
     */
    Response<SshConnectionResponseDTO> getConnection(String connectionId);

    /**
     * 查询当前认证设备的所有连接
     */
    Response<List<SshConnectionResponseDTO>> getConnectionList();

    /**
     * 测试SSH连接，不保存连接
     */
    Response<Void> testConnection(SshConnectionRequestDTO requestDTO);

    /**
     * 建立SSH连接
     */
    Response<Void> connect(String connectionId);

    /**
     * 断开SSH连接
     */
    Response<Void> disconnect(String connectionId);
}
