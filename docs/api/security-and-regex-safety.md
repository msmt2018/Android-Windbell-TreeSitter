# 安全、资源隔离与 Regex 安全 API 规范

## 威胁模型

.tsh 配置和 Regex 规则可能来自第三方或用户编辑内容。它们可能包含超大输入、复杂回溯表达式、递归引用、恶意 Provider 或过量装饰。TSH 必须将配置视为不可信输入。

## 目标 API

| API | 职责 |
|---|---|
| TshExecutionBudget | 时间、步骤、内存和匹配数量预算 |
| TshRegexSafetyPolicy | Regex 超时、最大输入、回溯和窗口策略 |
| TshConfigTrustPolicy | 配置来源和允许能力 |
| TshProviderPermission | Provider 声明的能力和资源权限 |
| TshResourceQuota | Session/Provider 的 CPU、内存和对象数上限 |
| TshSafeMode | 关闭可疑规则或隔离 Provider |
| TshSecurityDiagnostic | 超限、拒绝和隔离原因 |

## Regex 保护

- 不在 UI 线程运行用户 Regex。
- 每条规则设置执行预算、最大扫描长度和取消点。
- 对可能灾难性回溯的模式执行静态风险检查或使用可中止后端。
- Oniguruma 调用必须使用有效连续窗口，不得传入悬空/不连续内存。
- 超时、预算耗尽和跨窗口语义不确定必须返回诊断。
- 不允许失败后静默返回“没有匹配”。

## 配置与 Provider 隔离

配置编译时检查递归、循环引用、超量规则、未知扩展和不允许的资源引用。Provider 默认不能访问文件系统、网络、反射私有成员或应用内部对象；需要权限的能力必须显式声明并由宿主授予。

## 降级和恢复

当某条规则超预算时，可禁用该规则或本次执行，并保留其他安全规则的结果。多次超限可进入 SafeMode。任何隔离动作都应在 IntegrationReport 中可见，允许用户定位规则和恢复。

## 验收

- 恶意 Regex 不会无限占用 UI 或工作线程。
- Provider 超限可隔离且不影响其他 Session。
- 不可信配置不能触发未授权文件/网络操作。
- 超限和安全拒绝均有可定位诊断。
