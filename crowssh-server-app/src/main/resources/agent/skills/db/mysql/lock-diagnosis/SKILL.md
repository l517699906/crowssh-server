---
name: lock-diagnosis
description: 使用 MySQL 数据锁等待关系、线程和事务 ID 排查阻塞，区分无等待、缺权限、采集未启用和不支持。
---

# MySQL 锁等待诊断

1. 调用 inspectLocks 获取当前采样的请求事务、阻塞事务和线程关系；必要时补充 inspectProcessList 查看有界状态摘要。
2. 核对 capabilityState：AVAILABLE 且零行仅表示当前采样未发现数据锁等待；不据此排除其他类型的锁或已经消失的瞬时阻塞。
3. PERMISSION_DENIED、DISABLED、UNSUPPORTED 和 TEMPORARILY_UNAVAILABLE 分别报告，不能把采集失败说成没有锁。
4. 报告阻塞关系、采样时间和证据局限。线程 ID、进程 ID、事务 ID 含义不同，不能互换来构造终止命令。
5. 不读取锁数据或原始事务 SQL，不用业务条件探测推断被遮盖内容。不要声称 AI 能看到控制台未提交数据。
6. 终止连接和任意事务控制不在当前工具权限内，不提供绕过方式。需要受支持的修改时另走精确 SQL 审批；拒绝、取消或失效后停止本轮工具调用。

取消操作不承诺事务已回滚；未获得确定终态时明确结果未知。
