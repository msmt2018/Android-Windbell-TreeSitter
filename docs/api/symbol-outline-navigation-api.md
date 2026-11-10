> **范围声明：非 TSH 核心 API。** 本文仅作为宿主 Editor 集成边界/历史设计参考，不构成 TSH 引擎公共 API。TSH 不负责通用编辑器功能；请以 docs/api/README.md 的 TSH 核心 API 总目录为准。

# Symbol、Outline、Folding 与 Navigation API

> 状态：目标契约（规范文档）；不代表对应运行时 API 已实现。

## 1. 目标

为宿主编辑器提供统一的符号树、文件大纲、代码折叠区间和源码导航能力。符号来源可以是 Tree-sitter AST/Query、ATS 结构规则或语言 Provider；这些来源只产生结构化结果，不建立新的语法执行管线。

## 2. 核心模型

- `TshSymbol`：稳定 symbolId、name、kind、range、selectionRange、containerId、detail、tags、origin。
- `TshSymbolKind`：File、Module、Namespace、Class、Interface、Enum、Function、Method、Property、Field、Variable、Constant、Type、Macro、Label、Other。
- `TshFoldingRange`：range、kind（Comment/Imports/Region/Code）、collapsedText、source。
- `TshNavigationTarget`：documentId、range、selectionRange、symbolId、origin。
- `TshSymbolSnapshot`：documentVersion、generation、symbols、diagnostics。

所有 range 使用统一坐标模型，并携带 documentId 与版本；不得将过期范围直接应用到新版本文档。

## 3. 服务契约

```kotlin
interface TshSymbolProvider : TshProvider {
    fun provideSymbols(
        document: TshTextDocument,
        snapshot: TshTextSnapshot
    ): TshResult<TshSymbolSnapshot>
}

interface TshFoldingProvider : TshProvider {
    fun provideFoldingRanges(
        document: TshTextDocument,
        snapshot: TshTextSnapshot
    ): TshResult<List<TshFoldingRange>>
}

interface TshNavigationProvider : TshProvider {
    fun resolveDefinition(
        document: TshTextDocument,
        position: TshSourcePosition
    ): TshResult<List<TshNavigationTarget>>
}
```

以上为接口形态示意，最终签名须在源码审查和 Kotlin/Java 互操作验证后冻结。

## 4. 合并与优先级

1. 结果必须保留 origin/providerId，不能丢失来源。
2. 相同范围和语义标识可去重；仅名称相同不能作为去重依据。
3. 宿主语言 Provider 可补充 Tree-sitter/ATS 未覆盖的语义信息。
4. 折叠区间必须合法、可嵌套且不越过文档边界；冲突区间通过稳定排序和显式优先级处理。
5. 引擎不得擅自修改宿主折叠状态；只提交候选范围。

## 5. 生命周期与线程

计算任务绑定 documentId、documentVersion 和 session。文档版本变化时，旧任务应取消或将结果标记为 stale。解析可在后台线程执行；应用 UI 的展开/折叠动作由宿主线程执行。

## 6. 错误语义

区分 ProviderUnavailable、UnsupportedSymbolKind、StaleDocumentVersion、InvalidRange、PartialResult 和 InternalFailure。部分 Provider 失败时允许返回有效部分结果，但必须附带诊断。

## 7. 验收测试

- 嵌套类/函数/命名空间符号树结构正确。
- Unicode 前后位置与 UTF-16/UTF-8 转换一致。
- 增量编辑后旧 symbol/folding 结果不会错误应用。
- 多 Provider 重叠结果可稳定合并。
- 折叠区间不越界、不产生非法交叉。
- 空文件、超大文件和解析错误文件均有定义行为。
