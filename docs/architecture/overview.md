# TSH 总体架构

## 1. 产品定位

公共 API 最高层规范唯一来源为 [TSH Public API 设计总规范与宿主接入契约](../api/public-api-design-principles-and-integration-contract.md)。本架构文档仅定义系统视图，不得覆盖其宿主所有权、能力协商、坐标和渲染契约。

TSH 不只是语法高亮函数集合，而是供应用层使用的语言配置、语法分析、文本对象适配、样式解析、渲染集成和诊断平台。引擎负责吸收不同编辑器架构的复杂性，向宿主提供稳定统一的契约。

“零障碍”不是承诺对任意未知私有控件自动兼容，而是保证每类能力都有标准 API、Provider 扩展点、明确的能力缺失报告和可执行的适配路径。

## 2. 分层

1. **应用集成层**：Android View、Editor、Document、TextBuffer、Layout、Renderer。
2. **对象与能力层**：Object Registry、Descriptor、Capability、Resolver、Adapter、Binding。
3. **语言与配置层**：TSH Lexer/Parser/AST/Schema/Validator/Formatter/Language Tooling。
4. **语法执行层**：Tree-sitter Parser/Tree/Query、ATS、SCM 简化语法、独立 Regex/Oniguruma。
5. **样式与渲染层**：Highlight、Scope、Attribute、Theme、Style Resolver、Render Instruction、Provider。
6. **基础设施层**：JNI、Native 生命周期、线程、缓存、事件、诊断、测试。

## 3. 端到端数据流

`.tsh` 源码 -> 词法/语法分析 -> AST -> Schema/类型/语义校验 -> 编译配置 -> Tree-sitter/ATS 与 Regex 执行 -> 统一匹配结果 -> 文档坐标范围 -> Highlight Scope/Style Resolver -> Render Instruction -> 目标 Renderer Provider -> 编辑器局部刷新 -> 运行诊断。

任何阶段失败，都必须保留来源位置、对象标识、阶段、错误码和可选修复建议。

## 4. 核心架构原则

- 公共 API 与内部实现分离；应用只依赖公共契约。
- 所有外部对象通过实例注册、描述符或 Provider 接入，不要求继承 TSH 基类。
- 能力按需声明；不能假设所有编辑器具有相同的 Layout、Selection 或绘制钩子。
- 对象身份、文档版本、文本坐标系和资源所有权必须明确。
- 文本范围统一使用明确坐标系，禁止混用 Tree-sitter UTF-8 byte 与 Android UTF-16 offset。
- 增量计算必须带文档版本与失效范围。
- 配置、语法、样式和渲染状态分离，主题切换不应无故重做语法解析。
- Provider 可注册、发现、按 API/Capability 版本协商、确定性选择、诊断、替换和注销；第三方回调在锁外执行。
- JNI 边界必须定义所有权、线程、异常和编码规则。
- 所有功能状态必须区分“设计/实现/编译/运行验证”。

## 5. 模块边界

- `api`：稳定公开契约与基础值对象。
- `core`：引擎实现、服务注册、会话和生命周期。
- `config`：TSH 配置加载、AST、Schema 与编译。
- `object`：外部对象注册、描述、能力发现。
- `text`：Document、Buffer、Position、Range、坐标转换。
- `editor`：编辑器适配与编辑器会话。
- `syntax`：Tree-sitter/ATS 查询对象。
- `regex`：Regex/Oniguruma 管道。
- `highlight`：匹配结果到样式的解析。
- `render`：渲染指令、调度和 Provider。
- `attribute`：属性、主题和样式映射。
- `binding`：对象/属性/事件/生命周期绑定。
- `diagnostics`：诊断、追踪、性能与兼容性报告。
- `integration`：Android 与第三方编辑器集成。
- `extension`：扩展、Provider 发现和版本协商。

现有 `WindbellTshEngine` 和 Native 核心在迁移期间作为兼容实现保留，不得未经测试直接删除或改变 ABI。
