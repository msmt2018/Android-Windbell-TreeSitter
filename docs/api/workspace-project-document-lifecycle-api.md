> **范围声明：非 TSH 核心 API。** 本文仅作为宿主 Editor 集成边界/历史设计参考，不构成 TSH 引擎公共 API。TSH 不负责通用编辑器功能；请以 docs/api/README.md 的 TSH 核心 API 总目录为准。

# Workspace、Project 与 Document Lifecycle API

> 状态：目标契约（规范文档）；不代表对应运行时 API 已实现。

## 1. 职责边界

Workspace 表示宿主授权的资源集合；Project 表示可选的项目语义边界；Document 表示可编辑或只读文本资源。TSH 不自行扫描用户磁盘，也不假设所有 URI 都对应本地文件。

## 2. 身份模型

- `TshWorkspaceId`：宿主分配的稳定 workspace identity。
- `TshProjectId`：宿主分配的 project identity，可为空。
- `TshDocumentId`：运行期文档 identity，不等同于路径。
- `TshDocumentUri`：规范化资源标识；路径大小写规则由文件系统 Provider 决定。
- `TshDocumentDescriptor`：URI、languageId、readOnly、version、projectId、metadata。

同一 URI 可存在多个独立编辑实例；文档 ID 必须区分实例，不能仅以路径作为全局 key。

## 3. 生命周期

建议状态：Discovered -> Open -> Dirty/Clean -> Closing -> Closed。未打开的文件可由 Workspace Provider 提供只读快照。Close 必须取消关联任务、释放文档级缓存和订阅；不得擅自保存宿主文档。

## 4. 服务契约

```kotlin
interface TshWorkspaceProvider : TshProvider {
    fun workspaces(): TshResult<List<TshWorkspaceDescriptor>>
    fun documents(workspaceId: String): TshResult<List<TshDocumentDescriptor>>
    fun resolveDocument(uri: String): TshResult<TshDocumentDescriptor?>
}
```

文件读取、写入、目录枚举和权限校验均由 Provider/宿主完成；TSH 只调用显式授权能力。

## 5. Language Routing

languageId 决定候选语言配置；扩展名只是提示，不能作为唯一依据。宿主可为文档指定 override。语言切换必须使相关解析树、Query、匹配缓存、语义缓存和渲染计划失效，并记录切换原因。

## 6. 验收

覆盖同 URI 多实例、重命名/移动、只读文档、虚拟 URI、项目切换、权限撤销、关闭时任务取消和语言 override。
