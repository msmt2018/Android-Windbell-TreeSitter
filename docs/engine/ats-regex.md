# Tree-sitter、ATS 与 Regex 双管道

## 1. 技术路线

TSH 执行层固定采用：
- Tree-sitter：语法树解析与增量树更新。
- ATS：Tree-sitter 查询扩展、规则表达能力和执行编排。
- SCM 简化语法：作为 ATS 体系的输入兼容/简化表达，不建立独立执行管道。
- Regex：独立的正则匹配管道。
- Oniguruma：Regex 管道的正则执行后端。

不引入 Monarch 或 TextMate 作为第三套执行管道。可借鉴其配置组织、scope、主题和诊断设计思想，但不得改变上述执行架构。

## 2. 统一结果模型

ATS 和 Regex 各自保留原生匹配语义，输出统一 MatchResult：来源、ruleId、capture、scope、documentVersion、coordinateSystem、range、priority 和诊断信息。合并规则由 Highlight Resolver 处理，不把两个管道伪装成同一匹配器。

## 3. Oniguruma 与非连续文本

Oniguruma 需要连续输入视图，而 Piece Tree/Rope/滑动窗口可能非连续。不得直接把不连续片段指针传给 Oniguruma。必须定义 TextSnapshot/Window Provider：提供连续窗口、边界上下文、绝对偏移映射和窗口生命周期。跨窗口匹配需处理最大回溯/前后文、块边界状态和规则语义；不能安全判定时必须明确限制或返回诊断，不得静默漏匹配。

## 4. 增量与缓存

缓存键包含规则版本、文档版本/变更代数、语言和窗口边界。编辑事件必须使受影响范围与依赖上下文失效。所有异步结果需验证版本后才能提交。
