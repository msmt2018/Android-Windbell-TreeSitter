# 公共 API 基础层（首个实现切片）

## 状态

- [x] 结构化诊断与结果类型
- [x] 文本坐标系、位置和范围基础类型
- [x] 对象 ID、能力和对象描述符
- [x] 基于实例身份的对象注册表
- [x] Provider 注册、优先级、API 版本范围、能力版本匹配与异常隔离（源码/测试源码，未运行）
- [x] OwnerScope、Attach/Detach、Attachment Report、Provider refresh（源码/测试源码，未运行）
- [x] Long SourcePosition/ByteRange 与 Native Int narrowing 检查
- [x] TextDocument/TextSnapshot/TextEdit/TextChange 抽象
- [x] Snapshot Lease、bounded chunk read、Edit 排序/重叠校验（默认 Lease 不 pin 可变宿主版本）
- [x] 内存文本 UTF-8/UTF-16/Code Point/Grapheme 边界转换（不成对 surrogate 拒绝；BreakIterator 不承诺完整 UAX #29）
- [x] 引擎创建、初始化、关闭状态机
- [x] 对应单元测试源码
- [ ] Piece Tree/Rope 索引化坐标 Provider
- [ ] Layout-aware 行列/屏幕坐标转换
- [ ] 本地 Gradle 编译验证
- [ ] Robolectric/设备端运行验证
- [ ] 现有 WindbellTshEngine 迁移接线

## 包

`com.zerostudio.idea.treesitter.tsh.api`

## TshEngine

```kotlin
val engine = TshEngine.create()
val initialized = engine.initialize()
```

引擎当前提供 `objects`、`providers` 和 `attachments` 管理器。`attach/inspectAttachment/refreshAttachment/detach` 提供首版宿主接入闭环；`TshOwnerScope.close()` 会自动 detach，TSH 只注销自己的对象索引，不关闭宿主对象。该首个切片尚未接管原有 Native 解析和高亮服务；既有 `WindbellTshEngine` 仍是当前执行入口。不得把新门面初始化误认为 Native 子系统已经初始化。

状态：CREATED -> INITIALIZED -> CLOSED。关闭后不能重新初始化；关闭会清理注册表。

## TshObjectRegistry

```kotlin
val descriptor = engine.objects.register(
    editor,
    type = "editor",
    capabilities = TshCapabilitySet.of(
        TshCapability("text.read"),
        TshCapability("render.custom")
    )
)
val sameInstance = engine.objects.find(editor)
val descriptorById = engine.objects.descriptor(descriptor.id)
engine.objects.unregister(descriptor.id)
```

注册按实例身份（`===`）判断，而不是按 `equals()`。按 ID 解析 Native 实例的内部入口不对宿主开放，避免绕过 Native Lease 直接 close；宿主通过 descriptor(id) 查询元数据。注册表在注销前持有强引用，宿主必须在对象生命周期结束时注销，或使用 OwnerScope/Attachment detach。重复注册同一实例且类型一致时更新能力和元数据并保留 ID；类型不一致时拒绝。

## Provider

Provider 必须声明稳定 ID、API 版本、能力集合，并对给定实例和描述符明确回答是否支持。注册中心已有优先级、API 版本范围、Capability 最低版本、确定性选择、缺失能力报告和 supports 异常隔离源码；Engine 可按 strictProviderValidation 将必需能力缺失作为失败或返回降级报告。Attach/Detach、OwnerScope 和 Attachment Report 已有首版源码及测试源码；仍待构建/并发验证、动态 Provider 自动刷新通知和与真实 Editor/Text/Renderer Provider 的端到端绑定。

## 诊断与结果

`TshDiagnostic` 包含稳定 code、severity、stage、message，以及可选 source/range/objectId/cause/relatedInformation。范围端点必须使用同一坐标系，结束 offset 不得小于开始 offset。

`TshResult.Success` 可携带非致命诊断；`TshResult.Failure` 必须至少携带一条诊断。

## 当前限制与下一步

1. 将错误码目录独立文档化，并增加诊断收集器/报告。
2. 增加宿主 Piece Tree/Rope 固定版本 Snapshot Lease、真实变更/销毁订阅和索引化坐标 Provider。
3. 将 Attach/Detach 与 Editor/Document/Text/Renderer 的真实 Provider 实现绑定，并完成并发生命周期测试。
4. 为旧 `WindbellTshEngine` 增加兼容 Facade，逐步将配置/高亮能力迁入服务。
5. 为 Piece Tree/Rope 实现无需全量 flatten 的坐标索引与快照 Provider。
6. 在可用构建环境运行 `android-tree-sitter` 单元测试并修正编译问题。

注意：API 文件已提交到开发分支，但当前执行环境没有仓库工作区和 Gradle 构建能力，因此不能声称代码已编译或测试通过。
