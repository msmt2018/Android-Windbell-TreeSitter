# 公共 API 设计与发布审查清单

任何准备进入 public API 的类型或方法，都必须完成本清单。未完成项必须记录为明确限制，不得默认通过。

## 最高层规范

- [ ] 符合 public-api-design-principles-and-integration-contract.md 的全部不可违反原则。
- [ ] 明确区分宿主对象所有权与 TSH 自有资源所有权。
- [ ] 有 Attach/Detach、能力协商和可检查的集成报告。
- [ ] 所有领域指定唯一 normative source，其他文档只概览并链接。

## 契约完整性

- [ ] API 解决的问题和目标宿主对象清晰。
- [ ] 参数、返回值、空值语义和默认值明确。
- [ ] 前置能力和状态要求明确。
- [ ] 同步/异步行为、取消语义明确。
- [ ] 线程和回调调度策略明确。
- [ ] 对象所有权、引用强弱和释放责任明确。
- [ ] 重复调用、并发调用和关闭后的行为明确。
- [ ] 失败路径有稳定错误码、诊断和恢复建议。

## Android/编辑器兼容性

- [ ] 不强制宿主继承 TSH 类型。
- [ ] 不强制宿主使用特定编辑器控件或 UI 框架。
- [ ] 不把 Activity、View、Canvas、Compose 类型泄漏到平台无关核心模块。
- [ ] View/Layout/Canvas 回调明确要求 UI 线程。
- [ ] 编辑器销毁后不会被 Registry/Provider 意外长期持有。
- [ ] Spannable 后端只移除 TSH 自有 Span。
- [ ] Canvas/Compose/Decoration 后端有独立 Provider 契约。

## 文本正确性

- [ ] 坐标类型显式标记，绝对 Offset 使用 Long，Native 边界显式检查转换，不混淆 UTF-8 byte 与 UTF-16 code unit。
- [ ] 覆盖补充平面字符、组合字符、Emoji ZWJ、CRLF/LF 和不成对 surrogate 的拒绝策略。
- [ ] 增量编辑携带 beforeVersion/afterVersion。
- [ ] 过期快照和异步结果会被拒绝。
- [ ] Piece Tree/Rope 不要求无条件 flatten 全文；Snapshot Lease/分块/范围读取契约完整。
- [ ] 窗口式 Regex 说明跨边界匹配语义和上下文策略。

## 渲染正确性

- [ ] 匹配结果、样式结果和绘制指令是不同阶段的数据模型。
- [ ] RenderPlan 携带 document/config/theme/viewport 版本。
- [ ] Provider 声明属性能力和降级行为。
- [ ] 区分指令生成、提交、invalidate 和真实 draw callback。
- [ ] 真实控件测试证明颜色/字体/装饰确实可见。
- [ ] 主题变化不无谓触发语法树全量重建。

## Kotlin/Java 与二进制兼容

- [ ] Java 调用示例可编译。
- [ ] Kotlin 调用示例可编译。
- [ ] 默认参数、inline/value class、sealed hierarchy 等 ABI 影响已评估。
- [ ] 公共类型不暴露内部实现类。
- [ ] Deprecated API 有替代接口和迁移期限。
- [ ] JNI 签名、注解生成和 Native 注册同步检查。

## 测试和发布门槛

- [ ] 单元测试覆盖成功、失败、边界和并发。
- [ ] API 兼容性检查通过。
- [ ] 模块 Gradle 编译通过。
- [ ] Robolectric/Instrumentation 覆盖 Android 对象生命周期。
- [ ] 至少一种真实编辑器控件端到端渲染通过。
- [ ] 文档、示例、错误码目录和 changelog 同步。
- [ ] 明确标注设计/实现/编译/运行/真机验证状态。
