# JNI 与 Native Bridge API 规范

> 本文为 JNI 架构概览；Native operation/ABI/handle/lifecycle 的唯一权威契约以 [native-jni-cpp-completeness-api.md](native-jni-cpp-completeness-api.md) 为准。

## 目标

JNI 是 Kotlin/Java 与 C/C++ 内核之间的内部桥接层。应用层公共 API 不应要求开发者直接调用 JNI、传递裸指针或理解 Native 数组布局。NativeBridge 必须提供稳定的所有权、编码、错误和线程边界。

## 目标分层

Kotlin Public API -> Native Facade -> JNI Adapter -> C ABI/Native Core -> Tree-sitter / Oniguruma。

Kotlin Public API 面向应用；Native Facade 负责生命周期和结构化类型；JNI Adapter 负责签名、数组与异常映射；C ABI 是可测试的 Native 契约；Native Core 实现解析和匹配。

## 目标 API

| API | 职责 |
|---|---|
| TshNativeRuntime | Native 初始化、能力查询和关闭 |
| TshNativeHandle | 类型化句柄，不向应用暴露裸指针 |
| TshNativeResult | Native 调用状态、数据和错误 |
| TshNativeBuffer | 编码明确的输入缓冲区 |
| TshNativeDiagnostics | Native 错误结构和源范围 |
| TshNativeCapabilities | 版本、ABI、Grammar、Regex 后端能力 |
| TshNativeResourceScope | 句柄统一释放和泄漏追踪 |

## 所有权规则

每个 Native handle 必须声明创建方、持有方、引用计数/独占所有权和释放方法。禁止将 jlong 直接视为永远有效的指针。句柄释放后再次使用必须返回 HANDLE_CLOSED/INVALID_HANDLE，不得访问已释放内存。

## 编码和范围

所有 Native 字符串必须声明 UTF-8 或 UTF-16。Tree-sitter byte offset 与 JVM UTF-16 code-unit offset 必须经显式转换。数组结果应带 schema/version/元素宽度/单位；禁止依赖隐式数组下标协议且没有版本字段。

## 错误处理

C++ 异常不得跨 JNI 边界传播。JNI 层将错误转换为结构化 NativeDiagnostic，包含稳定 code、stage、message 和可选范围。不得仅返回 Boolean 或空数组隐藏失败。旧 Boolean 方法可保留兼容，但新 Facade 必须暴露完整结果。

## 线程和取消

每个 Native 对象声明线程安全级别：THREAD_SAFE、SESSION_SERIALIZED 或 THREAD_CONFINED。JNI 长任务应支持取消或在安全检查点中止。Native 回调进入 JVM 时不得在持有不必要的 Native 全局锁时调用用户代码。

## 生成链同步

新增/修改 Native 方法必须同步更新 Kotlin/Java 声明、JNI 注册表、注解处理器/生成器、C++ 实现、构建脚本和测试。CI 应检查声明与实现签名一致，并对 public JNI API 生成变更清单。

## 验收

- Native handle 重复释放和 use-after-free 有安全测试。
- JNI 字符串、数组和异常路径完整测试。
- 32/64 位与目标 ABI 的句柄宽度安全。
- Native 资源在 Session/Engine close 后释放。
- JNI API 变更自动检查声明、生成物和实现同步。


## 完整性检查

更完整的 Kotlin/JNI/C++ operation catalog、注解/注册生成同步、typed handle、generation、ownership、lease、错误码和生命周期契约见 [native-jni-cpp-completeness-api.md](native-jni-cpp-completeness-api.md)。
