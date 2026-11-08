# Kotlin、JNI、C++ Native API 完整性契约

> 状态：目标契约。Native 是 TSH 两条执行管线及资源管理的实现层；公共应用 API 不暴露裸指针。

## 1. 三层接口同步

Kotlin Public Facade → Kotlin Native Adapter → JNI entrypoints → C ABI facade → C++ core。每项能力必须有一致的 operation ID、参数语义、结果结构、错误码、版本和所有权规则。JNI 不允许成为未文档化的第二套公共 API。

## 2. Native operation catalog

至少登记 runtime init/capabilities/shutdown；config compile/install/unload；grammar load/release；parser create/parse/edit/release；ATS query compile/execute/release；regex compile/execute/release；match result access/release；scope/attribute/style resolve；font/color resolve；render plan build/submit/receipt；diagnostic retrieval。每个 operation 定义输入编码、offset 单位、线程约束、取消点、返回句柄和错误映射。

## 3. 类型化句柄和所有权

~~~c
typedef uint64_t tsh_handle_t;
typedef enum tsh_handle_kind {
  TSH_HANDLE_RUNTIME, TSH_HANDLE_CONFIG, TSH_HANDLE_GRAMMAR,
  TSH_HANDLE_PARSER, TSH_HANDLE_TREE, TSH_HANDLE_QUERY,
  TSH_HANDLE_REGEX, TSH_HANDLE_MATCH_SET, TSH_HANDLE_RENDER_PLAN
} tsh_handle_kind;
typedef struct tsh_status {
  int32_t code;
  uint32_t stage;
  const char* message_utf8;
} tsh_status;
~~~

Handle table 保存 kind、generation、owner runtime、refcount/lease 和 closed 状态。禁止直接把 jlong 当裸指针。释放后 generation 失效，重复 release 返回稳定状态；use-after-free 必须在访问 Native 内存前被拒绝。返回的数组/字符串由明确的 release API 或复制语义管理。

## 4. JNI 边界

- C++ exception 不得跨 JNI；统一 catch 并转换 status/diagnostic。
- 每个 JNI 方法检查 null、长度、范围溢出、编码合法性和句柄 kind。
- UTF-8/UTF-16 转换显式执行；Tree-sitter byte offset 与 Kotlin UTF-16 offset 不可混用。
- 不长期保存 GetStringChars/GetByteArrayElements 指针；需要持有时复制到 Native-owned buffer。
- 大结果采用可分块读取或直接 buffer 协议，必须带 schema version、element width、count 和 offset unit。
- JNI local/global reference 生命周期明确；禁止无界 global ref 泄漏。

## 5. 注解与生成同步

公开 JNI API、native method declaration、JNI registration table、C header、Kotlin external declaration 必须来自同一 manifest 或可校验生成流程。CI 检查：
1. Kotlin external 方法与 JNI 注册签名一一对应；
2. C/C++ 导出符号与 header 声明一致；
3. annotation processor/KSP/KAPT 生成文件与源声明一致；
4. 删除/重命名 API 时检测孤立 JNI symbol；
5. 生成文件可重复构建且 diff 为空。

生成注解必须携带 API version、handle kind、threading 和 ownership metadata；生成器失败必须使构建失败，不能静默回退到旧表。

## 6. 生命周期与并发

Runtime 关闭阻止新 operation；子句柄在 runtime close 时失效或按明确租约延迟释放。Parser 同一实例串行 parse/edit；不同实例可并发。Query/Tree/Grammar 之间采用 lease 保证执行期间资源存活。回调不得在持有全局 Native lock 时进入 JVM。JNI attach/detach 规则必须定义。

## 7. 错误码

统一错误域覆盖 INVALID_ARGUMENT、INVALID_HANDLE、HANDLE_KIND_MISMATCH、HANDLE_CLOSED、ABI_MISMATCH、ENCODING_ERROR、RANGE_ERROR、OUT_OF_MEMORY、CANCELLED、TIMEOUT、BUDGET_EXCEEDED、UNSUPPORTED_CAPABILITY、NATIVE_FAILURE。Kotlin 映射为 TshResult/TshDiagnostic；不得只返回 Boolean 或吞掉 Native 错误。

## 8. 验收

ABI/signature golden test、JNI registration test、注解生成一致性、重复释放、错误句柄类型、并发关闭、异常转换、Unicode offsets、Native leak/ASAN、长时间 parse/query/regex 和 Android ABI matrix。当前仅为目标契约，不代表生成器或完整桥接已实现。
