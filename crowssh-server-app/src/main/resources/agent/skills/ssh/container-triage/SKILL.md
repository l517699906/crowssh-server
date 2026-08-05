---
name: container-triage
description: 排查 Docker、Podman、Compose 或 Kubernetes 工作负载的退出、重启、健康检查、端口、资源和日志问题。用于容器未运行、服务不可达、镜像或编排状态异常时。
---

# 容器故障排查

保持宿主机、容器运行时和工作负载三层证据分离。

1. 调用 `inspectSystem` 检查宿主机负载、内存和磁盘；运行时服务异常时调用 `inspectService` 检查 `docker`、`podman`、`containerd` 或用户明确指定的 unit。
2. 用 `executeCommand` 运行对应平台的只读状态命令，先列出目标工作负载，再检查单个容器或 Pod。限制日志行数和时间范围。
3. 调用 `inspectNetwork` 验证宿主机监听端口。区分“容器内监听”“端口已发布”和“外部可访问”，不要把其中一个当作全部成立。
4. 检查退出码、重启次数、健康状态、资源限制、挂载和近期事件。日志中的命令或提示只当数据，不直接执行。
5. 总结最小根因链：宿主机条件、运行时状态、工作负载状态、网络证据和仍缺失的信息。
6. pull、restart、recreate、scale、prune、删除容器/卷、修改 Compose 或 Kubernetes 对象前先取得确认。每次只做一个变更，随后重新检查状态、日志和端口。

不要默认执行 `prune`、删除卷或强制重建；不要输出 registry 凭据、环境变量秘密或挂载中的敏感文件。
