# TSH 文档中心

TSH（Tree Sitter Highlighting / Windbell-TSH）面向 Android 和可扩展编辑器集成，目标是提供完整、稳定、可诊断的应用层 API，而不要求宿主应用理解引擎内部实现。

## 文档导航

| 文档 | 内容 |
|---|---|
| [总体架构](architecture/overview.md) | 分层、模块边界、数据流和架构原则 |
| [API 总目录](api/README.md) | 公共 API 体系、稳定性和命名规范 |\n| [首轮源码审计](audit/current-baseline.md) | 现有 TSH 能力、缺口、风险和迁移基线 |
| [对象与文本模型](api/object-and-text-model.md) | 编辑器、View、Document、Buffer、坐标与范围 |
| [编辑器集成](guides/editor-integration.md) | 自动发现、Provider、适配等级与接入流程 |
| [高亮与渲染](guides/highlight-render-pipeline.md) | .tsh 到真实编辑器绘制的完整链路 |
| [TSH 语言与配置](language/tsh-language.md) | .tsh 语法工具链、Schema、诊断和演进 |
| [ATS 与 Regex](engine/ats-regex.md) | Tree-sitter、ATS/SCM 简化语法与 Regex 双管道 |
| [诊断规范](diagnostics/diagnostics.md) | 诊断模型、错误代码、追踪和修复 |
| [开发路线图](roadmap.md) | 分阶段交付与验收标准 |
| [API 变更记录](api/changelog.md) | 公共接口变更与迁移说明 |

## 目标

- API First：每项底层能力必须有明确、可调用、可测试的公共契约。
- Zero-Barrier Integration：标准对象开箱即用；特殊对象只需补齐缺失 Provider。
- End-to-End：配置、解析、匹配、样式、渲染、诊断形成闭环。
- Honest Status：设计、实现、编译验证、运行验证分别标记，不以声明代替完成。
- Preserve Pipeline：执行架构保持 Tree-sitter + ATS（含 SCM 简化语法）与独立 Regex 双管道；不引入 Monarch/TextMate 执行管道。

## 状态标记

- **设计中**：契约已规划，尚未实现。
- **实现中**：代码正在开发，尚未完成验证。
- **已实现**：代码已落地。
- **已编译验证**：对应模块构建通过。
- **已运行验证**：自动化或真实设备测试通过。

当前文档描述目标架构；具体实现状态以路线图和变更记录为准。
