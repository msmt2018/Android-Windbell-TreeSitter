# Grammar、Parser 与 Query API 规范

## 目标边界

Grammar 管理语言定义与 Tree-sitter Language 资源；Parser 管理解析器实例和解析任务；Query 管理结构匹配规则。三者需要独立生命周期，不能将 Grammar、Parser、Tree 和 Query 混成一个全局单例。

## 目标 API

| API | 职责 |
|---|---|
| TshGrammarDescriptor | languageId、grammarVersion、ABI、资源来源和能力 |
| TshGrammarProvider | 加载、验证和释放 Grammar |
| TshGrammarRegistry | Grammar 注册、查找、冲突与卸载 |
| TshParserFactory | 按语言和配置创建 Parser |
| TshParserSession | Parser 实例、超时、取消和解析选项 |
| TshSyntaxTree | 不可变/受控访问的语法树快照 |
| TshQueryCompiler | 编译 ATS 查询及诊断 |
| TshQuerySession | 查询执行、捕获和局部范围 |
| TshParseResult | tree、版本、耗时、错误节点和诊断 |

以上为目标契约，不代表这些类型均已落地。

## Grammar 生命周期

Grammar 必须声明 Tree-sitter ABI 兼容范围、语言标识、版本、外部扫描器需求、查询能力和 Native 资源释放策略。注册时执行 ABI/符号自检；失败不得进入可用状态。卸载前必须确认没有活动 Parser/Tree 引用，或使用引用计数/租约延迟释放。

## Parser 约束

- Parser 实例不跨不兼容语言复用。
- 单个 ParserSession 的 parse/change 操作必须串行化。
- 不同文档可使用不同 ParserSession 并行执行。
- 解析结果携带 documentVersion 和 grammarVersion。
- cancellation 必须能在可控边界生效。
- 错误节点和缺失节点应保留为可查询结构，不得直接丢弃。
- 输入编码与 Tree-sitter byte offset 的映射必须由 TextSnapshot/CoordinateConverter 提供。

## Query 约束

ATS 查询编译结果应缓存，缓存键包括 grammar identity/version、query source hash、ATS language version。查询执行支持取消、超时、范围限制、capture 过滤和确定性排序。Query 失败必须返回 query source range 和具体错误。

## 增量解析

编辑事件以 beforeVersion/afterVersion 和精确编辑范围提交。引擎先验证版本连续性，再将编辑映射到 Tree-sitter InputEdit；版本缺失或不连续时执行受控全量重解析并产生降级诊断。不得将旧 Tree 与新文本混用。

## 验收

- 多语言 Grammar 并存，卸载不会释放仍在使用的 Native 资源。
- 增量编辑与全量解析的最终语法树等价。
- Query 编译错误包含准确源范围。
- 并发文档解析互不污染。
- 旧版本 ParseResult 不会覆盖新版本结果。
