# Binding、事件总线与扩展 API 规范

## Binding 的定位

Registry 解决“这个实例是谁”；Provider 解决“这个实例能做什么”；Binding 解决“这个实例在当前 Session 中承担什么角色，并与哪些对象关联”。三者不能混为一个全局对象表。

## 目标 API

| API | 职责 |
|---|---|
| TshBindingManager | 创建、查询、更新和释放绑定 |
| TshBinding | source、target、role、scope、状态和版本 |
| TshBindingOptions | 必需/可选、单向/双向、生命周期策略 |
| TshEventBus | 事件订阅和发布 |
| TshSubscription | 订阅句柄及幂等取消 |
| TshEvent | session/document/object/version/timestamp 元数据 |
| TshProviderDescriptor | Provider ID、版本、能力、优先级和来源 |
| TshExtensionManager | Provider 安装、启用、禁用和卸载 |

## Binding 约束

- Binding 必须有明确 source、target、role 和 owner Session。
- 同一对象可存在多个不同 role 的 binding；相同 role 是否允许多个绑定由 role schema 决定。
- 双向绑定必须定义冲突策略和循环更新防护。
- binding close 必须幂等。
- 对象 unregister 或 Session close 时，相关 binding 自动失效。
- Binding 不得偷偷接管宿主对象所有权。

## 事件规范

事件至少分为 Engine、Session、Object、Document、Config、Syntax、Highlight、Render、Diagnostic、Provider 和 Lifecycle 类别。事件 payload 必须不可变，并携带必要版本。高频文本变化可合并批处理，但必须保留版本顺序和最终文本状态。

## 事件线程

事件必须声明 dispatch policy：CALLER、ENGINE_WORKER 或 HOST_UI。UI 事件只能经由宿主 UI dispatcher。订阅回调不得在内部锁持有期间执行。异常应转换为 PROVIDER_CALLBACK_FAILED 诊断，不得破坏事件分发器。

## Provider 协商

匹配条件包括对象实例、对象类型、必需能力、能力版本区间、API 版本和优先级。注册冲突、版本不兼容、多个同优先级 Provider、Provider 自检失败均应产生诊断。Provider 查询不得自动执行 attach；发现与绑定是分开的步骤。

## 扩展安全

Provider 默认不具备文件系统、网络或反射私有成员权限。扩展注册时声明资源需求和生命周期回调。卸载时先停止新任务，再取消旧任务、解绑对象、释放资源，最后从 Registry 移除。

## 验收

- 多个 Provider 注册顺序变化不改变确定性选择结果。
- Provider 回调抛异常不会造成死锁或中断其他订阅。
- 对象销毁后 binding/subscription 不再触发。
- 重复 close/unregister 安全且无资源泄漏。
