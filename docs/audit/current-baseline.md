# 现有实现审计基线（第一轮）

> 范围：已读取 `WindbellTshEngine.kt`、`ts_windbell_jni.cpp`、`WindbellTshCore.h`、`WindbellTshCompiler.h`、现有 TSH Kotlin 测试和模块 Gradle 配置。此报告是静态源码审计，不代表构建或设备运行验证。

## 1. 已有能力

| 能力 | 当前入口/实现 | 静态审计结论 |
|---|---|---|
| .tsh 加载 | `WindbellTshEngine.loadTshSource` -> JNI -> `TshCompiler.compile` | 已有编译入口，返回 Boolean，缺少结构化诊断 |
| 主题调色板 | `nativeGetPalette` | 以扁平 IntArray 导出 styleId/light/dark |
| 混合高亮 | `nativeHighlight` -> `WindbellEngine.highlightHybrid` | Native 核心有 ATS/AST 与 Regex 混合高亮入口 |
| 折叠 | `nativeComputeFolds` | 有折叠范围输出 |
| Outline | `nativeGetOutline` | 通过 String 数组传递数字和名称 |
| Hover | `nativeGetHoverInfo` | 通过 String 数组传递范围、节点类型和详情 |
| Android Span | `applyHighlightsToSpannable` | 存在直接 Spannable 写入路径 |
| 测试 | `WindbellTshEngineTest`、`JavaTshFullTest` | 覆盖配置加载、部分高亮、Unicode 边界、折叠/大纲和并发场景的测试源码 |

## 2. 关键缺口

1. **没有统一 Engine 公共门面**：当前入口是单例 `WindbellTshEngine.instance`，没有标准 Engine 状态、Session、服务注册和关闭协议。
2. **没有通用对象注册/发现**：引擎无法统一识别宿主 Editor、Document、Buffer、Layout、Renderer 的具体实例。
3. **没有 Capability/Provider 契约**：无法声明、协商或诊断编辑器已有绘制/文本访问能力。
4. **应用层渲染耦合**：`WindbellTshEngine.kt` 直接依赖 Android `SpannableStringBuilder`、`CharacterStyle`、`ForegroundColorSpan` 和 `StyleSpan`，没有独立 Render Instruction/Provider。
5. **Span 清理范围过宽**：当前实现移除目标 Spannable 中所有 `CharacterStyle`，可能误删宿主应用或其他库添加的样式。后续必须只清理 TSH 自己拥有的 Span，或使用可识别的 TSH Span 标记。
6. **坐标模型未公开**：内部将返回范围右移一位换算 UTF-16 code unit，但没有公共 Position/Range/CoordinateSystem 和版本化转换契约。
7. **配置诊断不完整**：Native 编译入口只返回 Boolean；没有 lexer/parser/schema/semantic diagnostic 列表及源范围。
8. **JNI 传输模型不统一**：部分结果使用 IntArray，部分使用 String[]；没有统一结果结构、错误码和版本协议。
9. **生命周期不完整**：现有单例与 Native Grammar Registry 没有面向宿主对象的统一 attach/detach/close 契约。
10. **缺少集成诊断链**：无法从配置加载追踪到真实 Renderer 回调完成。

## 3. 首批新 API 已落地（尚待构建验证）

新增包：`com.zerostudio.idea.treesitter.tsh.api`

- `TshEngine`：平台无关生命周期门面。
- `TshResult` / `TshDiagnostic`：结构化结果与诊断基础类型。
- `TshCoordinateSystem` / `TshSourcePosition` / `TshSourceRange`：显式坐标基础模型。
- `TshObjectRegistry` / `TshObjectDescriptor`：按实例身份注册外部对象。
- `TshCapability` / `TshCapabilitySet`：对象能力描述。
- `TshProvider` / `TshProviderRegistry`：Provider 注册与对象匹配。
- `TshApiFoundationTest`：对应单元测试源码。

这一切片没有替换既有 Native 执行链，也没有声称完成编辑器自动适配或真实渲染。

## 4. 需要优先修复/验证的既有风险

- 验证 Native span 范围到底是 UTF-16 code-unit 还是以 2 为单位的 byte offset；统一命名和转换，禁止依赖隐式移位。
- 验证 Tree-sitter 节点范围与 Native UTF-16 文本扫描范围是否在非 ASCII 字符前后严格一致。
- 修复 Span 清理策略，保证不删除外部样式。
- 将 Outline/Hover 的 String[] 数字协议迁移为类型化结构（保持旧 JNI 兼容一段时间）。
- 将配置编译 Boolean 改造为结构化 CompilationResult，并保留旧 Boolean API 作为兼容包装。
- 增加真实编辑器 Renderer Provider 测试，不以 SpannableStringBuilder 测试替代真实控件绘制验证。

## 5. 下一轮审计

- 完整检查 `WindbellTshCompiler.cpp` 词法/语法规则、错误恢复与源范围。
- 检查 `WindbellTshCore.cpp` ATS/Regex 执行、优先级、冲突、增量与线程锁。
- 检查 `CMakeLists.txt` 与 JNI 注册/注解生成链。
- 检查 `android-tree-sitter` 模块 API 兼容性和实际 Gradle 构建。
