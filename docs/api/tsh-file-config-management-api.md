# .tsh 文件与配置对象管理 API 契约

> 状态：目标契约，尚不代表已有完整实现。本文件定义应用如何管理 TSH 配置文件及其内部配置对象，不定义文件浏览器或编辑器文档 API。

## 1. 职责边界

TSH 管理配置源及其编译产物：发现候选源（由宿主 Resolver 提供）、加载、解析、校验、编译、注册、查询、替换、卸载、依赖追踪和诊断。目录选择器、文件浏览 UI、编辑缓冲区和保存按钮由宿主提供；TSH 只消费 Source/Resolver 抽象。

配置对象包括 LanguageDefinition、GrammarBinding、QuerySet、RegexSet、ScopeDefinition、SelectorDefinition、AttributeDefinition、ThemeDefinition、StyleRule、FontSpec、ColorSpec、DecorationSpec、VariableDefinition 和 InjectionDefinition。

## 2. 配置来源与加载

~~~kotlin
interface TshConfigSource {
  val uri: String
  val format: TshConfigFormat
  val revision: String?
  fun open(): TshInput
}
interface TshConfigResolver {
  fun resolve(reference: TshConfigReference, from: TshConfigSource): TshResult<TshConfigSource>
}
interface TshConfigLoader {
  fun load(source: TshConfigSource): TshResult<TshConfigDocument>
}
~~~

支持文件 URI、Asset、应用私有资源、内存字符串和宿主自定义 Source。相对引用以声明该引用的配置源 URI 为基准；禁止隐式从任意工作目录搜索。加载结果保留 URI、编码、revision、内容摘要和源范围映射。

## 3. 编译与发布

~~~kotlin
interface TshConfigCompiler {
  fun compile(document: TshConfigDocument, options: TshCompileOptions = TshCompileOptions()): TshCompilationResult
}
data class TshCompilationResult(
  val diagnostics: List<TshDiagnostic>,
  val artifact: TshConfigArtifact?,
  val dependencies: List<TshConfigDependency>
)
interface TshConfigRepository {
  fun install(artifact: TshConfigArtifact): TshResult<TshConfigHandle>
  fun replace(handle: TshConfigHandle, artifact: TshConfigArtifact): TshResult<TshConfigHandle>
  fun unload(handle: TshConfigHandle): TshResult<Unit>
  fun find(id: TshConfigId): TshConfigHandle?
  fun list(filter: TshConfigFilter = TshConfigFilter()): List<TshConfigDescriptor>
}
~~~

编译失败不得改变当前生效配置；install/replace 以不可变 Artifact 原子提交；replace 成功后新请求使用新 revision，已开始的请求可完成旧 revision，但结果必须携带 configRevision；unload 后拒绝新任务并等待或取消持有资源的任务，再释放 Native/缓存资源。

## 4. 对象查询与管理

必须提供统一只读查询接口和受控变更接口，不暴露可变内部 Map。操作至少包括：

- Theme：get、list、resolve、preview、activate、reset。
- StyleRule：get、list、resolve、explain。
- Font：get、list、resolve、fallback 查询和资源可用性诊断。
- Color：get、list、parse、resolve。
- Scope/Selector/Attribute：get、list、validate、resolve/explain。
- Grammar/Query/Regex：get、list、compile、validate、执行诊断。
- Injection：get、list、验证嵌套关系和范围映射。
- Config：load、reload、validate、compile、install、replace、unload、查询 revision/dependencies/capabilities/diagnostics。

~~~kotlin
interface TshConfigCatalog {
  fun themes(filter: TshThemeFilter = TshThemeFilter()): List<TshThemeDescriptor>
  fun theme(id: TshThemeId): TshResult<TshThemeDefinition>
  fun styles(themeId: TshThemeId, filter: TshStyleFilter = TshStyleFilter()): List<TshStyleRule>
  fun fonts(filter: TshFontFilter = TshFontFilter()): List<TshFontDefinition>
  fun colors(filter: TshColorFilter = TshColorFilter()): List<TshColorDefinition>
  fun scopes(languageId: TshLanguageId? = null): List<TshScopeDefinition>
  fun attributes(namespace: String? = null): List<TshAttributeDefinition>
}
~~~

定义对象不可变，并带来源 URI、声明 ID、版本和源范围。不存在、歧义、引用循环、版本不兼容必须返回不同稳定错误码。

## 5. 修改、覆盖与热更新

权威输入仍是 .tsh 源码。运行时对象不提供无约束 setter。应用可使用 ConfigPatch 构建变更提案，但必须映射回正式 AST 节点并经过同一 Schema/Validator/Compiler：

~~~kotlin
interface TshConfigTransaction {
  fun set(path: TshConfigPath, value: TshValue): TshResult<Unit>
  fun remove(path: TshConfigPath): TshResult<Unit>
  fun preview(): TshCompilationResult
  fun commit(): TshResult<TshConfigRevision>
  fun rollback()
}
~~~

事务校验 baseRevision；并发 revision 不一致返回 CONFIG_REVISION_CONFLICT；commit 失败不污染现有状态。主题切换、字体资源变更、样式规则替换均产生新 revision，并触发精确缓存失效和 RenderPlan 重建。

## 6. 依赖、线程与卸载

依赖图覆盖 include/import、grammar、query、regex、theme extends、变量引用、font/color 引用和 injection。循环依赖在编译期诊断。卸载被其他 Artifact 引用的对象时默认拒绝并报告引用者；级联卸载必须显式选择。

Source 读取和编译可在后台执行；Catalog 对不可变快照可并发读取。不在锁内调用用户 Source/Resolver 回调。文件大小、引用深度、对象数量、正则预算和字体资源大小均有配额。外部字体经 TshFontResourceProvider 加载，不允许任意路径或隐式网络访问。

## 7. 验收

覆盖多来源加载、相对引用、重复 ID、缺失依赖、循环依赖、编译失败回滚、原子替换、revision 冲突、并发读取、卸载引用保护和资源释放。当前仅为契约文档。
