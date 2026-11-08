> **范围声明：非 TSH 核心 API。** 本文仅作为宿主 Editor 集成边界/历史设计参考，不构成 TSH 引擎公共 API。TSH 不负责通用编辑器功能；请以 docs/api/README.md 的 TSH 核心 API 总目录为准。

# Completion、Hover、Definition、References 与 Rename API

> 状态：目标契约（规范文档）；不代表对应运行时 API 已实现。

## 1. 目标

提供一致的语言智能请求/响应模型。能力由 Tree-sitter AST/Query、ATS 结构匹配、Regex 规则以及可选语言 Provider 协作提供；ATS 与 Regex 仍为独立管线，统一层只负责结果归一化和调度。

## 2. 请求上下文

每个请求必须携带 sessionId、documentId、documentVersion、position、triggerKind、cancellationToken（或等价取消句柄）和 capability snapshot。请求可携带 selectionSet、workspace scope 与客户端能力。

## 3. 结果模型

- `TshCompletionItem`：label、kind、detail、documentation、sortText、filterText、insertText/edit、additionalEdits、commitCharacters、source。
- `TshHover`：内容片段、标记语言、range、来源。
- `TshLocation`：documentId/URI、range、selectionRange。
- `TshReferenceResult`：locations、isComplete、diagnostics。
- `TshRenameEdit`：按文档分组的版本化 TextEdit 集合。
- `TshWorkspaceEdit`：documentChanges、resourceOperations（仅在宿主声明支持时）。

## 4. 服务接口形态

```kotlin
interface TshLanguageIntelligenceProvider : TshProvider {
    fun complete(request: TshCompletionRequest): TshResult<TshCompletionList>
    fun hover(request: TshPositionRequest): TshResult<TshHover?>
    fun definition(request: TshPositionRequest): TshResult<List<TshLocation>>
    fun references(request: TshReferenceRequest): TshResult<TshReferenceResult>
    fun rename(request: TshRenameRequest): TshResult<TshWorkspaceEdit>
}
```

接口签名是契约草案；异步执行、Java 互操作和取消机制需在实现阶段统一，不允许让同步重计算阻塞 UI 线程。

## 5. 编辑安全

Rename 必须先完成符号解析与可编辑性校验，再返回版本化 WorkspaceEdit；不能直接写文件。每个 TextEdit 必须基于请求时文档版本。宿主应用编辑前应验证所有目标版本，提供原子应用或明确的部分失败报告。

Completion 的 additionalEdits 不得与主 edit 或彼此重叠，除非协议明确允许且宿主支持。插入文本与范围必须经过坐标系统校验。

## 6. 多来源聚合

聚合器按 provider priority、来源可信度和明确的排序字段处理结果；不得仅凭字符串相同合并语义不同的条目。Provider 失败时允许部分结果，并在 diagnostics 中记录。排序应稳定，避免每次键入列表无故跳动。

## 7. 安全与隐私

documentation/hover 内容作为不可信文本处理；不得默认执行 HTML/脚本。Workspace 查询应受项目根目录与授权范围限制。Rename 不得跨越用户未授权的 workspace。

## 8. 验收测试

测试触发字符、空结果、取消、过期版本、跨文件引用、重名符号、重叠编辑、只读文件、Provider 部分失败、Java 调用和大工作区响应。
