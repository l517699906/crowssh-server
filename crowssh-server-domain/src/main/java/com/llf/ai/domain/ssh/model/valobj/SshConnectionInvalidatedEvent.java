package com.llf.ai.domain.ssh.model.valobj;

/** 已认证的连接操作断开旧 SSH 传输后，通知依赖资源撤销旧绑定。 */
public record SshConnectionInvalidatedEvent(String ownerId, String connectionId) { }
