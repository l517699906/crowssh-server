package com.llf.ai.domain.agent.model.valobj.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@AllArgsConstructor
@NoArgsConstructor
public enum AgenttypeEnum {

    Loop("循环执行", "loop", "loopAgentNode"),
    Parallel("并行执行", "parallel", "parallelAgentNode"),
    Sequential("串行执行", "sequential", "sequentialAgentNode");

    private String name;
    private String type;
    private String node;

    public static AgenttypeEnum formType(String type) {
        if (type == null) {
            return null;
        }

        for (AgenttypeEnum value : values()) {
            if (value.getType().equals(type)) {
                return value;
            }
        }

        return null;
    }
}
