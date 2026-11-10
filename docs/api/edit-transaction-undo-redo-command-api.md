> **范围声明：非 TSH 核心 API。** 本文仅作为宿主 Editor 集成边界/历史设计参考，不构成 TSH 引擎公共 API。TSH 不负责通用编辑器功能；请以 docs/api/README.md 的 TSH 核心 API 总目录为准。

# EditTransaction、Undo/Redo 与 Command API

> 状态：目标契约（规范文档）；不代表对应运行时 API 已实现。

## 1. 目标

定义 TSH 与宿主编辑器之间的安全编辑事务协议。文档存储、撤销栈和实际文本修改默认由宿主拥有；TSH 可生成版本化编辑建议、命令描述和事务约束。

## 2. 核心模型

- `TshEditTransaction`：transactionId、documentId、baseVersion、edits、origin、label、atomicity。
- `TshEditOperation`：range、replacement、expectedText/hash（可选）、coordinateSystem。
- `TshCommandDescriptor`：commandId、arguments schema、requiredCapabilities、trustLevel。
- `TshUndoGroup`：宿主生成的 undo group identity 与 transaction mapping。
- `TshApplyEditResult`：appliedVersion、appliedCount、rejectedEdits、diagnostics。

## 3. 应用协议

1. TSH 生成只读、不可变的事务提案。
2. 宿主校验 documentId、baseVersion、范围、重叠和权限。
3. 宿主决定原子应用或拒绝；默认多文档事务要求原子性，若宿主不支持必须明确降级。
4. 宿主应用后回传新版本与实际结果。
5. TSH 只在收到确认后更新派生缓存，不得假定编辑已经成功。

## 4. Undo/Redo 所有权

撤销/重做栈由宿主 Editor/Document 管理。TSH 可提供事务分组建议和来源元数据，但不得自行维护一份与宿主不同步的权威撤销栈。撤销/重做完成后，宿主发出带版本的 DocumentChange，触发解析和渲染增量更新。

## 5. 命令安全

命令必须注册、声明参数 schema、能力要求和权限级别。未知命令、参数不匹配或能力不足时返回结构化错误。命令执行不得隐式访问任意文件、网络或进程；危险能力必须由宿主显式授权。

## 6. 并发与冲突

同一文档并发事务按 baseVersion 检测冲突。若宿主支持 rebase，必须提供可验证的映射策略；否则返回 VersionConflict。不得静默把旧范围套用到新文本。事务幂等性由 transactionId 定义，重复提交不得重复应用。

## 7. 验收测试

过期版本、重叠 edit、空事务、重复提交、多文档部分失败、undo/redo 往返、命令权限拒绝、宿主取消、应用回调丢失和事务确认后的缓存一致性。
