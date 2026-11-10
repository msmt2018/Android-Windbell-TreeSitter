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
- 对长时间同步读取可使用作用域化 GetStringChars，但必须在后续 JNI 分配/回调前释放；大结果必须分批 SetArrayRegion，避免再创建同尺寸临时 vector。

当前旧 TSH 高亮 JNI 已改为在纯 Native 计算作用域内持有 GetStringChars，不再创建额外完整 UTF-16 vector；Highlight/Fold 输出以固定批次写入 Java 数组。此路径仍接收完整 Java String，并不等于 Piece Tree/Rope 流式解析或零拷贝宿主接入。
- 大结果采用可分块读取或直接 buffer 协议，必须带 schema version、element width、count 和 offset unit。
- JNI local/global reference 生命周期明确；禁止无界 global ref 泄漏。

## 4.1 当前 bounded TSInput JNI bridge

当前生效的 Android binding 位于 `android-tree-sitter/src/main/cpp/ts_parser.cc`，不是仓库中未纳入根 Gradle settings 的旧 `treesitter/` 目录。

- `TSParser.parseInput()` 使用 Java `TSInput.read(byteOffset, row, column, destination)`，编码固定为 UTF-16LE；不创建全文 String 或全文 byte array。
- 每次 parse 只创建一个可复用的 Java `byte[]`。JNI 在一次 read 回调返回后保留当前 `GetByteArrayElements` 指针，直到下一次 read 回调或 parse 结束，再以 `JNI_ABORT` 释放。
- 回调必须返回 0..destination.length 内的偶数 byte count；奇数 byte offset、超出 Java signed point 范围、负数/超容量/奇数长度均拒绝。
- Parser Owner 在 Native parse 前对可复用 Java byte[] 和可能的 JNI copy 预留整次 parse 的输入预算；每次 provider 返回的 UTF-16 临时 chunk 另行按回调生命周期预留。
- Java read/progress callback 异常必须让 parse 停止；一旦 JNI exception pending，后续 read/progress callback 不再进入 JVM。若 Native 已产生部分 Tree，JNI 在异常返回 Java 前删除该 Tree，避免把半成品发布给调用方。
- `GetByteArrayElements` 失败且 JNI 没有 pending exception 时显式抛出 `OutOfMemoryError`，不得把内存分配失败误判为 EOF。JNI 测试覆盖 read callback 异常传播及奇数 byte count 拒绝。
- Tree-sitter parser/tree 内部 allocator 的实际占用仍不由这组输入缓冲区 reservation 覆盖；Native Tree 只能使用宿主估算器进行 admission accounting。

## 4.2 Tree-sitter core allocator 的预算边界

Tree-sitter core 的 `ts_set_allocator()` 是库级全局分配器切换点，不是 Parser/Tree 级 allocator。切换后仍存活的对象必须由兼容旧分配状态的新 allocator 释放，因此不能在每次 parse 前后临时替换 allocator 来做 per-parser 预算。

默认 allocator 在 malloc/calloc/realloc 失败时会 abort；仅让自定义 allocator 在超过预算时返回 null，不能构成安全的可恢复 OOM 路径，因为 core 的每条分配调用链并未统一暴露可恢复错误状态。

因此当前 TSH 不通过 allocator hook 声称对 Tree-sitter Native heap 实施硬上限。若后续要做真实 Native 内存遥测，必须采用进程级常驻 allocator 包装、逐块记录 size/allocator provenance，并验证 realloc/free、跨线程释放、所有 Tree/Query/Language 对象的完整生命周期；即使遥测准确，也要单独设计可恢复的 Native allocation failure 协议，才能谈硬拒绝。

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
