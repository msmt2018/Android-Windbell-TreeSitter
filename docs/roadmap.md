# TSH 统一 API 建设路线图

## 阶段 0：源码审计与契约冻结
- 审计 Kotlin/Java 公共入口、Native 核心、JNI、配置语法、测试和构建。
- 建立 API 清单、重复/缺失/断链清单。
- 定义稳定包结构、坐标和错误模型。
- 产出：审计报告、API catalog、架构决策记录。

## 阶段 1：公共 API 基础
- TshEngine/TshContext 生命周期门面。
- 结构化 TshResult/TshError/TshDiagnostic。
- Object ID、Capability、Descriptor、Registry。
- Provider 注册/发现契约。
- 保留 WindbellTshEngine 兼容入口。

## 阶段 2：最小文本与坐标适配
- TextSnapshot、TextAccessProvider、Position/Range/CoordinateSystem。
- UTF-8/UTF-16 转换与版本校验。
- 仅提供 TSH 匹配/渲染所需的最小宿主适配，不实现 Selection/Cursor/EditorSession 等编辑器功能。

## 阶段 3：TSH 语言工具链
- Lexer/Parser/AST/Schema/Validator。
- 多级诊断、格式化、Schema/类型/语义校验和版本迁移。
- 版本化语法规范与迁移。

## 阶段 4：ATS/Regex 统一结果
- MatchResult、来源追踪、规则 ID、范围和文档版本。
- Oniguruma 连续窗口适配。
- 增量失效和缓存策略。

## 阶段 5：DSL 配置管理与样式系统
- .tsh Source/Loader/Compiler/Repository/Catalog/Transaction。
- Theme/Style/Font/Color/Scope/Selector/Attribute 的 get/list/resolve/管理 API。
- Scope、Selector、属性类型、主题继承、颜色空间、完整字体属性和 Decoration。
- 样式更新与匹配缓存解耦。

## 阶段 6：Render 闭环
- RenderInstruction、RenderProvider、调度和局部失效。
- Android Canvas/Span Provider 与编辑器原生 Renderer Provider。
- 真正验证目标编辑器绘制回调。

## 阶段 7：Binding/Event/Lifecycle
- 对象、文档、属性、事件和渲染绑定。
- 自动解绑、取消异步任务和资源释放。

## 阶段 8：JNI/Native 完整性
- Java/Kotlin 与 Native 契约同步。
- 编码、异常、所有权、线程和资源测试。
- JNI 声明/注册/注解生成同步检查。

## 阶段 9：集成、兼容和性能
- 多类宿主渲染后端参考适配（不扩展通用 Editor 功能）。
- Robolectric/设备端集成测试。
- 大文档、增量、Unicode、并发和内存测试。

## 完成判定

每项功能分别标记设计、实现、编译验证、自动化运行验证、真实编辑器验证。阶段不能仅凭文件存在或编译通过判定完成。当前首个实现切片为阶段 1 的 API 基础，不代表后续阶段已完成。

## API 规范文档映射

阶段交付必须同步更新对应 API 契约：

- 阶段 1：`api/core-foundation.md`、`api/runtime-and-session.md`。
- 阶段 2：`api/object-and-text-model.md`、`api/editor-adapters.md`。
- 阶段 3：`api/config-and-language-tooling.md`、`api/tsh-dsl-syntax-and-semantic-contract.md`。
- 阶段 5：`api/tsh-file-config-management-api.md`、`api/theme-style-font-color-api.md`、`api/tsh-core-api-coverage-matrix.md`。
- 阶段 4：`api/ats-regex-and-match-api.md`。
- 阶段 5/6：`api/highlight-and-render-api.md`。
- 阶段 7：`api/binding-events-and-extension.md`。
- 全阶段：`api/diagnostics-and-observability.md`、`api/public-api-review-checklist.md`。

- 阶段 2：补充 `api/selection-cursor-multicursor-api.md`、`api/symbol-outline-navigation-api.md`。
- 阶段 3：补充 `api/language-intelligence-api.md`、`api/injection-and-language-layer-api.md`。
- 阶段 5/6：补充 `api/decorations-gutter-inlay-codelens-api.md`。
- 阶段 7/9：补充 `api/edit-transaction-undo-redo-command-api.md`。

新增公共 API 必须先更新契约和示例，再实现接口；实现后补齐测试、兼容性说明和 changelog。没有对应测试与状态标记的文档条目不得标记为已完成。


## 核心边界纠偏

TSH 不实现通用编辑器的折叠、Outline、符号导航、光标/选区、多光标、Undo/Redo、命令、Gutter、Inlay 或 CodeLens。嵌入语言语法注入、Scope/Selector、属性、Theme/Style/Font/Color/Decoration 和真实高亮渲染属于 TSH 核心。每一项 DSL 声明必须映射到 Parser/Schema/Compiler/Runtime/Renderer 的实际消费点。
