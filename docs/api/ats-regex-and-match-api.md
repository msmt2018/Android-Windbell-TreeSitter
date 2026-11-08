# Tree-sitter、ATS 与 Regex 匹配 API 规范

## 固定执行架构

TSH 只维护两条匹配管道：

1. Tree-sitter AST + ATS（包含受控的 SCM 简化语法）：基于语法树节点和结构查询。
2. Regex：独立正则表达式管道，用于非结构化文本、局部模式和补充规则。

TextMate/Monarch 的设计思想可以作为配置组织、作用域命名、优先级、注入和主题机制的参考，但不得引入为第三条执行管道。

## 目标 API

| API | 职责 |
|---|---|
| TshSyntaxService | Tree-sitter Language/Parser/Tree 生命周期 |
| TshGrammarRegistry | Grammar 注册、版本和语言映射 |
| TshAtsCompiler | ATS/SCM 简化语法编译 |
| TshAtsQuery | 结构查询及捕获定义 |
| TshRegexCompiler | Regex 编译与引擎能力检查 |
| TshRegexQuery | Regex 规则及 flags、region、timeout |
| TshMatchEngine | 两条管道调度和结果汇总 |
| TshMatch | 统一范围、来源、规则、capture 和优先级 |
| TshConflictResolver | 重叠范围、优先级和合并策略 |

## 统一 Match 结果

每个 Match 至少包含 documentId、documentVersion、start/end、coordinateSystem、pipeline（ATS 或 REGEX）、ruleId、captureName、priority、scope 和 sourceRange。不得只返回颜色或不可解释的匿名整数数组。

## ATS 规则

ATS 编译阶段必须验证节点类型、字段名、捕获名、谓词和语言 Grammar 是否存在。运行时查询必须支持取消、超时预算、增量范围和查询错误诊断。查询结果范围以 Tree-sitter byte offset 为来源，再由 CoordinateConverter 转换。

## Regex 规则

Regex 必须定义字符编码、flags、region、最大输入范围、超时/取消策略和灾难性回溯防护。不得让单条恶意表达式无限占用 UI 线程。Regex 不应假设底层 Piece Tree 文本连续；通过 TextAccessProvider 的分块扫描/窗口策略处理，并明确跨窗口匹配边界。

## 两条管道的冲突

匹配引擎只汇总 Match，不在管道内部偷偷写 UI Span。冲突策略必须明确：优先级、范围包含/相交、属性合并、capture 归属及稳定排序。ATS 与 Regex 的先后不能依赖 HashMap 遍历顺序。

## 验收

- ATS 能基于 AST 匹配结构节点，Regex 能独立运行。
- 两条管道统一输出同一种 Match 数据模型。
- 超时、取消、无效 Query、未知 Grammar 都有诊断。
- UTF-8 byte 到 UTF-16 范围转换覆盖补充平面和组合字符。
- 大文件/ Piece Tree 场景不要求无条件 flatten 全文。


## 双管线专门契约

- ATS 与 Tree-sitter：见 [ats-tree-sitter-query-api.md](ats-tree-sitter-query-api.md)。
- Regex 连续窗口、跨窗口等价、增量和 Oniguruma buffer lease：见 [regex-window-execution-api.md](regex-window-execution-api.md)。
- 统一 Match/Scope 与冲突：见 [unified-match-scope-api.md](unified-match-scope-api.md)。

两管线只在 Match 结果层汇合，不共享或混淆底层执行器。
