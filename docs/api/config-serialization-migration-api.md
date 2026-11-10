# Configuration Serialization、Persistence 与 Migration API

> 状态：目标契约（规范文档）；不代表对应运行时 API 已实现。

## 1. 目标

定义 .tsh 配置、主题、语言注册元数据和用户覆盖项的序列化、持久化与版本迁移。TSH 语言配置仍由自身 Lexer/Parser/Schema/Validator 处理；本 API 不引入 TextMate/Monarch grammar 格式作为运行时配置。

## 2. 配置身份

每份配置应包含 schemaId、schemaVersion、languageId、configId、contentHash 和 source URI（如有）。配置内容与编译后的缓存产物分离；缓存不得成为唯一权威数据。

## 3. 读取与写入

- 读取返回原始内容、解析版本、来源和诊断。
- 写入必须通过宿主授权的 ConfigStore Provider。
- 原子写入应采用临时文件/替换或宿主等价机制。
- 未知字段可按 schema policy 保留或报告，不得静默丢弃用户数据。
- 配置加载失败时可选择显式 fallback，但必须暴露 fallback 原因和当前生效配置身份。

## 4. Migration

迁移器按 sourceVersion -> targetVersion 注册，要求确定性、可重复测试。迁移不得直接覆盖原始文件；先产出迁移结果和 diagnostics，再由宿主确认持久化。禁止跳过不支持的中间版本，除非迁移器明确声明可跨版本。

## 5. 安全

配置文件和外部引用均视为不可信输入。限制文件大小、嵌套深度、规则数量和 Regex 资源预算；拒绝路径穿越与未授权 include。敏感字段不得进入日志。

## 6. 验收

旧版本迁移、未知字段、损坏配置、原子写入失败、重复迁移幂等性、include 权限、缓存与原始配置不一致。
