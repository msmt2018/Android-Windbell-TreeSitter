# 诊断与可观测性规范

## 1. 统一诊断结构

诊断字段至少包含：
- code：稳定机器可读错误码。
- severity：ERROR/WARNING/INFO/HINT。
- stage：CONFIG/LEX/PARSE/SCHEMA/SEMANTIC/COMPILE/SYNTAX/ATS/REGEX/HIGHLIGHT/COORDINATE/BINDING/RENDER/JNI/LIFECYCLE。
- message：用户可读说明。
- source：文件 URI 或对象来源。
- range：可选源范围，必须声明坐标系。
- objectId/documentId：适用时提供。
- cause/relatedInformation：根因和关联位置。
- actions：可选修复建议。
- traceId/documentVersion：关联一次运行链路。

## 2. 分层报告

- ConfigReport：文件加载、解析、Schema、编译结果。
- ObjectInspectionReport：识别到的对象和能力。
- IntegrationReport：Provider、缺失能力、兼容性。
- PipelineTrace：ATS/Regex 执行、匹配数量和耗时。
- HighlightReport：规则命中、scope 和样式来源。
- RenderReport：指令数量、Provider、提交/刷新结果。
- PerformanceReport：阶段耗时、缓存命中、任务取消。
- NativeReport：JNI 错误、资源和 Native 调用状态。

## 3. 不允许的行为

- 不得把所有失败都折叠为 Boolean false。
- 不得吞掉异常或静默返回空列表来伪装成功。
- 不得把“指令已生成”描述为“屏幕已绘制”。
- 不得把未执行的 Provider 能力标记为已支持。
- 诊断本身失败时要保留可访问的基础日志。

## 4. 诊断链示例

CONFIG_LOADED -> ATS_QUERY_EXECUTED -> REGEX_MATCHED -> RANGES_MAPPED -> STYLES_RESOLVED -> INSTRUCTIONS_CREATED -> PROVIDER_SELECTED -> SUBMITTED -> INVALIDATED -> DRAW_CALLBACK_CONFIRMED。

最后一步需要 Provider 提供可验证的完成信号；仅调用 invalidate 不代表已经绘制。
