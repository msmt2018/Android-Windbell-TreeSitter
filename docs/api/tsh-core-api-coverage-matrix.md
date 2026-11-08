# TSH DSL 能力—底层 API 契约覆盖矩阵

> 本矩阵用于阻止“DSL 声明存在但引擎没有消费实现”。状态必须区分规范、接口源码、执行实现、编译验证、自动化测试和宿主真实渲染验证。

| .tsh 能力/语法对象 | 必需底层 API | 必须产生的运行时结果 | 关键验收 |
|---|---|---|---|
| 顶层版本、include/import | Source、Loader、Parser、DependencyResolver | 版本化 ConfigArtifact | 版本拒绝、引用解析、循环检测 |
| language、grammar | LanguageDefinition、GrammarRegistry、ParserFactory | 可执行 GrammarBinding | 装载/释放、版本兼容 |
| query、ATS | Tree-sitter Parser、Query Compiler/Executor | TshMatch | capture、predicate、增量查询 |
| regex | Regex Compiler/Executor、Window Adapter | TshMatch | 跨窗口、超时、捕获组、Unicode |
| scope、selector | ScopeRegistry、SelectorParser/Matcher | ScopePath、SelectorMatch | 组合语义、specificity、性能上限 |
| attribute | AttributeSchema、AttributeRegistry、Resolver | TypedAttributeSet | 类型校验、默认值、继承和冲突 |
| theme、variables | ThemeRegistry、VariableResolver | ThemeSnapshot | 继承、循环引用、热切换 |
| color | ColorParser、ColorValue、ColorResolver | ColorToken/ResolvedColor | 格式、alpha、颜色空间 |
| font | FontSpecParser、FontResolver、FontResourceProvider | ResolvedFont | family、weight、style、size、fallback、axes |
| style | StyleRegistry、CascadeResolver | TshResolvedStyle | 规则匹配、属性来源解释 |
| decoration | DecorationResolver、RenderInstruction | DecorationInstruction | 类型、层级、合成和降级 |
| injection | InjectionResolver、LanguageRegionMapper | 嵌套语言 Match | 区域映射、边界和增量失效 |
| render | RenderPlanBuilder、RenderBackend、Receipt | 可确认的绘制提交 | foreground/background/font/decoration 真实可见 |
| config management | ConfigRepository、Catalog、Transaction | 可查询/替换/卸载的 revision | 原子替换、失败回滚、依赖保护 |
| diagnostics/trace | DiagnosticSink、TraceCollector | 阶段诊断与规则来源链 | 每阶段错误定位、命中解释 |

## 应用可调用的管理操作

配置源：load、reload、validate、compile、install、replace、unload。主题：list、get、preview、activate、reset。样式规则：list、get、resolve、explain。字体：list、get、resolve、fallback 查询、资源可用性诊断。颜色：list、get、parse、resolve。Scope/Selector/Attribute：list、get、validate、resolve/explain。Grammar/Query/Regex：list、get、compile、validate、执行诊断。快照：查询 revision、dependencies、capabilities、diagnostics 和 close。

## 禁止的虚假完成判定

文档写了语法，不等于 Parser 接受；Parser 接受，不等于 Schema/TypeChecker 正确；Compiler 生成对象，不等于运行时管线消费；Resolver 算出颜色/字体，不等于应用层绘制；RenderPlan 生成，不等于 Backend 已提交；Backend 提交，不等于真实控件正确呈现。

每个矩阵单元必须单独维护实现与验证状态。当前本矩阵是验收规范，不是实现报告。


## 七组 API 文档映射

1. DSL 类型系统与 Schema：`dsl-type-system-and-schema-api.md`。
2. ATS/Tree-sitter 查询：`ats-tree-sitter-query-api.md`。
3. Regex 连续窗口与增量：`regex-window-execution-api.md`。
4. 统一 Match/Scope/Selector：`unified-match-scope-api.md`。
5. Attribute/Semantic/Highlight：`attribute-semantic-highlight-api.md`。
6. Theme/Style/Font/Color/Render：`theme-style-render-contract-api.md`。
7. Kotlin/JNI/C++ 同步：`native-jni-cpp-completeness-api.md`。

对每项能力，发布状态需分别记录：DSL 语法、Parser、Schema、Compiler lowering、Runtime consumer、Native bridge、Render consumer、单元测试、集成测试、设备验证。任一环节缺失都不能标为完整支持。
