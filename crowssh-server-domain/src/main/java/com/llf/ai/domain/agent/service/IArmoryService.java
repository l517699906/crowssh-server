package com.llf.ai.domain.agent.service;

import com.llf.ai.domain.agent.model.valobj.AiAgentConfigTableVO;

import java.util.List;

public interface IArmoryService {

    void acceptArmoryAgents(List<AiAgentConfigTableVO> tables) throws Exception;
}
