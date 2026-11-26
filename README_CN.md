<div align="center">
  <img src="logo.png" alt="Android Windbell Tree-sitter 标志" width="180" />
  <h1>Android Windbell Tree-sitter</h1>
  <p><strong>面向 Android 的 Tree-sitter JNI 绑定、多语言语法模块与 Windbell TSH 高亮引擎。</strong></p>
  <p><a href="README.md">English</a> · <a href="https://github.com/msmt2018/Android-Windbell-TreeSitter">GitHub 仓库</a> · <a href="https://tree-sitter.github.io/tree-sitter/">Tree-sitter</a> · <a href="https://jitpack.io/#msmt2018/Android-Windbell-TreeSitter">JitPack</a></p>
</div>

---

## 项目简介

**Android Windbell Tree-sitter** 是面向 Android 编辑器和开发工具的 Tree-sitter 集成项目。仓库包含统一 JNI 绑定层、Tree-sitter Native 运行时、独立语言语法模块，以及 **Windbell TSH（Tree-sitter Highlighting）高亮引擎**。

项目面向需要语法树解析、语言结构信息、增量文档处理和语法高亮能力的 Android 应用。

### 核心能力

- **Android JNI 绑定：** 由唯一的正式绑定模块 <code>android-tree-sitter</code> 向 Java / Kotlin 暴露 Tree-sitter API。
- **模块化 Grammar：** 各语言解析器可独立构建为 Android Native Library，应用按需依赖语言模块。
- **Windbell TSH：** 使用 <code>.tsh</code> 配置文件，组合 Tree-sitter ATS 选择器 / Native Tree-sitter Query 与可选的独立 Oniguruma Regex 规则执行路径。
- **语义样式与主题：** <code>.tsh</code> 可声明语言元数据、编辑器行为、样式目录、浅色 / 深色调色板和高亮规则。
- **大文档基础设施：** Native 文本源适配器包含有界窗口读取、映射文件 / 文件窗口和 Piece Tree 支持，并提供增量解析及视口捕获接口供编辑器集成。
- **Android 渲染集成：** 编辑器侧适配层将 TSH 捕获结果和解析后的样式转换为 Android 文本 Span。

TSH 高亮体系严格采用 **Tree-sitter ATS / Tree-sitter Query + 可选 Regex**。项目不使用 Monarch 高亮管线，也不依赖生成的 <code>.scm</code> 高亮管线。

> 大文件支持属于架构能力，不代表所有设备、语法和编辑器场景都具有固定性能保证。实际解析、捕获和最终屏幕绘制性能取决于语法复杂度、文档结构、设备内存和编辑器集成方式。评估目标工作负载时应以仓库测试和真实设备测量为准。

## 引擎架构

~~~text
Android 编辑器 / 应用
        |
        +-- 编辑器集成与 Android Span 渲染
        |
        +-- Windbell TSH Kotlin API
        |      |
        |      +-- .tsh 解析、编译、样式与调色板
        |      +-- Tree-sitter ATS / Native Query 执行
        |      +-- 可选 Oniguruma Regex 执行
        |
        +-- android-tree-sitter JNI 绑定层
               |
               +-- Tree-sitter Native Runtime
               +-- 自动生成的 Grammar JNI 库
               +-- Native TextSource / Piece Tree / 映射文件适配器
~~~

<code>android-tree-sitter</code> 是项目唯一正式 JNI 绑定层，也是 TSH 引擎的归属模块。Grammar 模块依赖该绑定层，不额外引入第二套 Tree-sitter 绑定。

### 仓库目录结构

| 路径 | 用途 |
| --- | --- |
| <code>android-tree-sitter/</code> | Android Java/Kotlin API、JNI 实现、Tree-sitter Native 集成、TSH 引擎与测试 |
| <code>grammars/</code> | Grammar Git Submodule 与语法元数据 |
| <code>grammar-modules/</code> | Gradle 根据 <code>grammars/grammars.json</code> 在项目配置阶段生成的语法模块 |
| <code>tree-sitter-lib/</code> | Tree-sitter 源码 Submodule，包含构建使用的 CLI 源码 |
| <code>editor/</code> | 编辑器集成、Piece Tree / TSH 适配 |
| <code>piecetable/</code> | Piece Tree 实现 |
| <code>annotations/</code>、<code>annotation-processors/</code> | Native Header 与 JNI 注册注解处理 |
| <code>build-logic/</code> | Gradle 插件、Grammar 模块生成和构建逻辑 |
| <code>app/</code> | Android 示例应用与集成测试 |
| <code>docs/</code> | TSH 语言规范、架构、渲染和开发文档 |

## 快速开始：使用 Tree-sitter

### 1. 添加 JitPack 仓库

在使用方 Android 项目的 <code>settings.gradle.kts</code> 中添加：

~~~kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
~~~

### 2. 添加核心绑定和所需 Grammar 依赖

本仓库属于多模块 Gradle 项目。JitPack 单独模块依赖的坐标格式如下：

~~~kotlin
dependencies {
    implementation("com.github.msmt2018.Android-Windbell-TreeSitter:android-tree-sitter:VERSION")
    implementation("com.github.msmt2018.Android-Windbell-TreeSitter:tree-sitter-java:VERSION")
}
~~~

将 <code>VERSION</code> 替换为已经由 JitPack 成功构建的 Git Tag 或 Commit 版本。每种语言按需添加一个 <code>tree-sitter-&lt;language&gt;</code> 模块，不需要默认引入全部 Grammar。

**当前发布状态说明：** 仓库现有 <code>publish-packages.yml</code> 工作流的发布目标是 **GitHub Packages**，不是 JitPack。仓库根目录目前没有 <code>jitpack.yml</code>，因此这里不宣称某个 Release Tag 已经通过 JitPack 构建。正式依赖前，请先在 [JitPack 项目页面](https://jitpack.io/#msmt2018/Android-Windbell-TreeSitter)确认构建成功，并确认所需模块已列出。多模块坐标格式及 Android 构建要求请参考 [JitPack Android 指南](https://docs.jitpack.io/android/) 和 [多模块构建说明](https://docs.jitpack.io/building/)。

### 3. 解析源码

下面使用自动生成的 Java Grammar 绑定：

~~~java
import com.zerostudio.idea.treesitter.TreeSitter;
import com.zerostudio.idea.treesitter.TSNode;
import com.zerostudio.idea.treesitter.TSParser;
import com.zerostudio.idea.treesitter.TSTree;
import com.zerostudio.idea.treesitter.java.TSLanguageJava;

public final class ParseExample {
    public static void parse(String source) {
        TreeSitter.loadLibrary();

        try (TSParser parser = TSParser.create()) {
            parser.setLanguage(TSLanguageJava.getInstance());

            try (TSTree tree = parser.parseString(source)) {
                if (tree == null) {
                    throw new IllegalStateException("Tree-sitter did not return a syntax tree");
                }

                TSNode root = tree.getRootNode();
                String rootType = root.getType();
                // 在这里读取节点类型、范围、字段和子节点。
            }
        }
    }
}
~~~

使用解析器 API 前先加载核心 Native Library。Native 对象应在所有权结束时及时释放；示例通过 try-with-resources 管理 <code>TSParser</code> 和 <code>TSTree</code>。

## 快速开始：加载 TSH 配置

TSH 引擎包含在 <code>android-tree-sitter</code> 模块中。Grammar 模块提供对应的 <code>TSLanguage</code> 实例，<code>.tsh</code> 文件提供高亮规则与样式配置。

~~~kotlin
import com.zerostudio.idea.treesitter.java.TSLanguageJava
import com.zerostudio.idea.treesitter.tsh.WindbellTshEngine

fun loadJavaTshProfile() {
    val language = TSLanguageJava.getInstance()
    val source = assets.open("tsh/java-ats-only.tsh")
        .bufferedReader()
        .use { it.readText() }

    val engine = WindbellTshEngine.instance
    val loaded = engine.loadTshSource(
        extension = "java",
        tshSourceContent = source,
        language = language
    )

    check(loaded) {
        engine.lastCompileDiagnostics.joinToString("\n") {
            it.line.toString() + ":" + it.column + " " + it.code + ": " + it.message
        }
    }
}
~~~

示例中的配置文件路径来自示例应用的 assets。实际应用可以将自己的 <code>.tsh</code> 配置放入 assets，或从应用配置源读取。

### TSH 文档与编辑器集成流程

编辑器集成的基本流程：

1. 加载 Grammar Native Library，并获取对应的 <code>TSLanguage</code>。
2. 调用 <code>WindbellTshEngine.loadTshSource</code> 加载并编译对应的 <code>.tsh</code> 配置。
3. 通过 <code>NativeTextWindowReader</code> 和 <code>createNativeDocument</code> 提供文档数据。
4. 调用 <code>highlightNativeDocument</code> 请求所需字节区间的高亮捕获结果。
5. 使用当前 TSH 调色板解析捕获结果中的样式 ID，并由编辑器渲染层应用样式。
6. 文档编辑提交后，将编辑区间同步到 Native 文档，并刷新失效的编辑器区间。
7. 编辑器会话结束时关闭 Native 文档。

Native 文档读取接口使用 **UTF-16LE 字节偏移量**，传入的偏移量和长度必须为偶数。对于大文档，应将 Reader 连接到编辑器 Piece Tree 或其他窗口化文本源；不要为了满足 Reader 接口而额外创建整份文档的完整字节数组副本。

实现参考：

- <code>editor/src/main/java/x/github/module/editor/tsh/TshPieceTreeEditorBinding.kt</code>
- <code>editor/src/main/java/x/github/module/editor/tsh/TshPieceTreeTextAccessProvider.kt</code>
- <code>editor/src/main/java/x/github/module/editor/tsh/TshPieceTreeIncrementalSession.kt</code>
- <code>app/src/main/java/com/zerostudio/idea/androidtreesitter/TshSyntaxHighlighter.kt</code>
- <code>android-tree-sitter/src/main/java/com/zerostudio/idea/treesitter/tsh/WindbellTshEngine.kt</code>

## 已有 Grammar Language

仓库当前包含以下 Grammar Submodule。Gradle 会根据 <code>grammars/grammars.json</code> 中的每个条目生成对应的 <code>tree-sitter-&lt;language&gt;</code> 模块。

| 分类 | Grammar 标识符 |
| --- | --- |
| Android 与 Native | <code>aidl</code>、<code>arm64</code>、<code>asm</code>、<code>c</code>、<code>cmake</code>、<code>cpp</code>、<code>disassembly</code>、<code>llvm</code>、<code>llvm_mir</code>、<code>smali</code> |
| Shell 与构建配置 | <code>bash</code>、<code>containerfile</code>、<code>dockerfile</code>、<code>gdb</code>、<code>make</code>、<code>starlark</code>、<code>zsh</code> |
| C 系与 JVM | <code>csharp</code>、<code>go</code>、<code>gomod</code>、<code>groovy</code>、<code>java</code>、<code>kotlin</code>、<code>rust</code>、<code>swift</code> |
| Web 与脚本语言 | <code>dart</code>、<code>javascript</code>、<code>jsdoc</code>、<code>lua</code>、<code>php</code>、<code>ruby</code>、<code>scss</code>、<code>svelte</code>、<code>typescript</code>、<code>vue</code> |
| 数据与配置 | <code>csv</code>、<code>gitattributes</code>、<code>gitcommit</code>、<code>gitignore</code>、<code>git_rebase</code>、<code>graphql</code>、<code>ini</code>、<code>json</code>、<code>logcat</code>、<code>markdwon</code>、<code>properties</code>、<code>proto</code>、<code>regex</code>、<code>requirements</code>、<code>sql</code>、<code>ssh</code>、<code>toml</code>、<code>xml</code>、<code>yaml</code> |
| 文档格式 | <code>asciidoc</code>、<code>diff</code>、<code>rst</code> |

以上标识符保持仓库实际 Grammar 目录名称，包括当前目录中使用的 <code>markdwon</code> 拼写。

Grammar 存在于 Git Submodule 中，不代表它已经发布到所有 Maven 仓库。添加 Maven 依赖前，应检查目标仓库中实际可用的模块和版本。

## Grammar 元数据生成：<code>generate_grammars.py</code>

在仓库根目录执行：

~~~bash
python generate_grammars.py
~~~

该脚本维护 <code>grammars/grammars.json</code>，Gradle 的 <code>DynamicModulePlugin</code> 会读取该文件并动态生成语法模块。

### 脚本具体工作

1. 读取 <code>.gitmodules</code>，提取所有注册在 <code>grammars/</code> 下的 Submodule 路径。
2. 将 Submodule 路径与当前已经检出的本地 Grammar 目录取交集。未初始化的 Submodule 不会被误认为本地可用语法。
3. 递归扫描 Grammar 目录，查找 <code>src/parser.c</code> 或 <code>src/parser.cc</code>。
4. 跳过隐藏目录，以及 <code>node_modules</code>、<code>.git</code>、<code>.github</code>、<code>build</code>、<code>target</code> 等排除目录。
5. 尽可能从 parser 源码中提取真实导出的 <code>tree_sitter_*</code> C 入口符号，而不是单纯猜测函数名称。
6. 自动识别 Composite Grammar，并记录嵌套的 <code>sub.paths</code> 与一一对应的 <code>sub.entries</code>。
7. 检测 <code>scanner.c</code> 或 <code>scanner.cc</code>，并记录到 <code>src.extra</code>。
8. 先写入临时文件，再生成 <code>grammars/grammars.json</code>。

JSON 使用两种结构描述 Grammar：

- 单解析器：使用 <code>entry</code> 记录 C 入口符号。
- Composite 多解析器：使用 <code>sub.paths</code> 和 <code>sub.entries</code> 记录各个子解析器及入口符号。

此外，<code>src.extra</code> 用于记录需要一起编译的外部 Scanner 源文件。Gradle Settings 插件读取 JSON 后，会在 <code>grammar-modules/&lt;language&gt;</code> 下生成模块构建文件、JNI glue 和 CMake 配置。

**特别注意：** <code>generate_grammars.py</code> 负责生成 Grammar 模块元数据，不负责生成 Tree-sitter parser 源码。被扫描的 Grammar Submodule 中必须已经存在 parser 源文件。Tree-sitter CLI / Gradle Grammar 任务负责 Parser 生成和 Native 编译。

新增、移除或调整 Grammar Submodule 后执行：

~~~bash
git submodule update --init --recursive
python generate_grammars.py
~~~

检查 <code>grammars/grammars.json</code> 的变更后再提交。

详细说明见：[README_grammars.md](README_grammars.md)。

## 从源码构建项目

### CI 使用的工具链版本

以下版本来自当前 GitHub Actions 构建工作流和 Gradle Version Catalog：

| 组件 | 版本 / 要求 |
| --- | --- |
| Gradle 运行 JDK | Temurin 17 |
| Gradle Wrapper | 8.14.8 |
| Android Gradle Plugin | 8.13.2 |
| Kotlin Gradle Plugin | 2.4.21 |
| Android NDK | r29 / <code>29.0.14206865</code> |
| Android CMake | 3.31.6 |
| Android Build Tools | 35.0.0 |
| Android compile SDK | API 34 |
| Android minimum SDK | API 21 |
| Python | 3.12 |
| Node.js | 22.22.2 |
| Rust / Cargo | 构建仓库内 Tree-sitter CLI 所需 |
| Bun | Grammar package 工作流使用 |
| Native 主机工具 | C/C++ 编译器、<code>build-essential</code>、CMake、Ninja |

注意：Gradle 运行环境的 JDK 版本与 Android 源码 / 字节码兼容级别是不同配置。构建项目时使用 **JDK 17**；共享 Android 编译配置目标为 Java 11。

### Windows 环境配置

最接近 CI 的 Windows 开发环境是 **Windows 10/11 + WSL2（Ubuntu）**。仓库 CI 运行在 Ubuntu，使用 Bash、Cargo、CMake、Ninja、Node package 安装和 Native 主机测试。

在 Windows Android Studio 中配置：

1. 安装 Android Studio 和 Android SDK。
2. 安装 JDK 17，例如 Eclipse Temurin 17。
3. 打开 **Android Studio → Settings → Build, Execution, Deployment → Build Tools → Gradle**，将 **Gradle JDK** 设置为 JDK 17。
4. 在 **SDK Manager → SDK Platforms** 中安装 Android API 34。
5. 在 **SDK Manager → SDK Tools** 中安装 Android SDK Build-Tools 35.0.0、NDK (Side by side) 29.0.14206865、CMake 3.31.6 和 Android SDK Command-line Tools。
6. 安装 Git for Windows（包含 Git Bash）、Python 3.12、Node.js 22.22.2、Rust/Cargo 和 Bun。确保终端能够找到 <code>git</code>、<code>bash</code>、<code>python</code>、<code>node</code>、<code>npm</code>、<code>cargo</code>、<code>cmake</code> 和 <code>ninja</code>。
7. 递归克隆仓库 Submodule，然后在 Android Studio 中打开仓库根目录。

~~~powershell
git clone --recurse-submodules https://github.com/msmt2018/Android-Windbell-TreeSitter.git
cd Android-Windbell-TreeSitter
git submodule update --init --recursive
python generate_grammars.py
.\gradlew.bat assembleDebug
~~~

Java Grammar ABI 重新生成任务会调用 Bash 脚本。如果在原生 Windows 环境构建，必须确保 Bash 已加入 <code>PATH</code>。需要执行与 CI 一致的 Host JNI 和 Sanitizer 测试时，建议使用 WSL2 Ubuntu；当前仓库工作流基于 Linux，Windows 尚未列入 CI 矩阵。

### Linux 环境配置（Ubuntu）

安装 CI 使用的 Native 系统依赖：

~~~bash
sudo apt-get update
sudo apt-get install -y build-essential cargo cmake ninja-build git curl unzip
~~~

安装并选择以下工具版本：

- Temurin JDK 17
- Python 3.12
- Node.js 22.22.2 与 npm
- Bun
- Android SDK Command-line Tools
- Android NDK 29.0.14206865
- Android CMake 3.31.6
- Android Build Tools 35.0.0

在 Android Studio 中将 **Gradle JDK 设置为 JDK 17**，并安装 Android API 34 及上述 SDK 工具。也可以配置 <code>ANDROID_SDK_ROOT</code> 指向 SDK 目录，并使用 <code>sdkmanager --licenses</code> 接受 Android SDK 许可证。

~~~bash
git clone --recurse-submodules https://github.com/msmt2018/Android-Windbell-TreeSitter.git
cd Android-Windbell-TreeSitter
git submodule update --init --recursive
python3 generate_grammars.py
chmod +x ./gradlew
./gradlew assembleDebug
~~~

### 安装 Grammar package 依赖

CI 工作流会扫描 Grammar Submodule 中的 <code>package.json</code>，安装各个独立 package 根目录，并跳过已经由 npm workspace 管理的子 package。安装命令使用 <code>npm install --legacy-peer-deps --no-audit --no-fund</code>，并发数量受到控制。

某个 Grammar 需要本地 npm 依赖时，在对应目录中安装。例如：

~~~bash
cd grammars/cpp
npm install
cd ../..
~~~

不要在每个嵌套 workspace 子目录中无差别重复执行 <code>npm install</code>；应先识别 workspace 根目录并只安装一次。

### 构建 Release APK

Linux / macOS / WSL2：

~~~bash
./gradlew assembleRelease generateDebugSymbolsRelease --parallel --stacktrace
~~~

Windows PowerShell：

~~~powershell
.\gradlew.bat assembleRelease generateDebugSymbolsRelease --parallel --stacktrace
~~~

示例 APK 输出目录为 <code>app/build/outputs/apk/release/</code>。

### 执行本地测试

CI 使用的主要本地测试命令：

~~~bash
./gradlew testDebug :editor:compileDebugAndroidTestKotlin :app:compileDebugAndroidTestKotlin --continue --parallel --stacktrace
~~~

Windows PowerShell：

~~~powershell
.\gradlew.bat testDebug :editor:compileDebugAndroidTestKotlin :app:compileDebugAndroidTestKotlin --continue --parallel --stacktrace
~~~

工作流还会在 Linux 主机执行 Native 测试，包括带 ASan/UBSan 的大文件存储测试和 Native TSH 缓存测试。对应的测试配置示例：

~~~bash
cmake -S android-tree-sitter/src/main/cpp/largefile/tests -B build/largefile-tests -DCMAKE_BUILD_TYPE=Debug
cmake --build build/largefile-tests --parallel 2
ASAN_OPTIONS=detect_leaks=1:halt_on_error=1 UBSAN_OPTIONS=halt_on_error=1:print_stacktrace=1 \
  ctest --test-dir build/largefile-tests --output-on-failure

./gradlew :android-tree-sitter:generateNativeHeadersDebug --stacktrace
cmake -S android-tree-sitter/src/main/cpp -B build/host \
  -DCMAKE_BUILD_TYPE=Debug \
  -DBUILD_TESTING=OFF \
  -DAUTOGEN_HEADERS="$PWD/android-tree-sitter/build/generated/native_headers"
cmake --build build/host --target android-tree-sitter --parallel 2
~~~

完整设备测试矩阵请查看 <code>.github/workflows/build.yml</code>。当前工作流使用 Android Emulator API 26、30 和 35。

### Android Studio 项目配置

- 打开仓库根目录，即包含 <code>settings.gradle</code> 的目录，不要单独打开某个子模块。
- 将 Gradle JDK 设置为 17。
- 通过 SDK Manager 安装 API 34、Build Tools 35.0.0、NDK 29.0.14206865 和 CMake 3.31.6。
- 等待 Gradle Sync 完成；Settings 插件会根据 <code>grammars/grammars.json</code> 生成 Grammar 模块。
- 不要手动修改 <code>grammar-modules/</code> 下的生成文件，应修改 Grammar 元数据或生成器输入。
- SDK 路径保存在本机 <code>local.properties</code> 中，不要提交机器专属 SDK 路径。

## Maven 包与 JitPack

需要区分两种不同的分发方式：

- **GitHub Packages：** 仓库当前的 <code>publish-packages.yml</code> 工作流将选定模块发布到 GitHub Packages。
- **JitPack：** 根据 Git Tag 或 Commit 构建源码，并从 <code>https://jitpack.io</code> 提供 Maven 依赖。

使用 JitPack 时，先在仓库查询页面确认目标 Tag / Commit 构建成功，并且目标模块已列出。对于本多模块仓库，单独模块的依赖坐标形式为：

~~~kotlin
dependencies {
    implementation("com.github.msmt2018.Android-Windbell-TreeSitter:android-tree-sitter:VERSION")
    implementation("com.github.msmt2018.Android-Windbell-TreeSitter:tree-sitter-java:VERSION")
}
~~~

以 JitPack 页面实际显示的 artifact 名称为准。将 Java 示例替换为应用所需的 Grammar 模块。Grammar 模块依赖核心绑定模块，因此通常同时添加核心绑定和所选语言模块。

分支快照或 Commit 构建应使用 JitPack 页面为对应分支或 Commit 提供的版本写法。JitPack 版本规则和构建要求可能变化，请以官方 [Android 发布指南](https://docs.jitpack.io/android/) 和 [构建指南](https://docs.jitpack.io/building/) 为准。

## 更多文档

- [Grammar 模块元数据与生成说明](README_grammars.md)
- [TSH DSL v2 规范](docs/TSH_DSL_V2_SPEC.md)
- [TSH 大文本 Native Token 架构](docs/TSH_LARGE_TEXT_NATIVE_TOKEN_ARCHITECTURE.md)
- [TSH 高亮准确性与渲染完整性](docs/TSH_HIGHLIGHT_ACCURACY_AND_RENDERING_INTEGRITY.md)
- [TSH 极限优化与闪烁修复](docs/TSH_EXTREME_OPTIMIZATION_AND_FLICKER_REMEDIATION.md)
- [TSH 引擎完成度与性能路线图](android-tree-sitter/TSH_ENGINE_COMPLETION_AND_PERFORMANCE_ROADMAP.md)
- [Java ATS-only 示例配置](app/src/main/assets/tsh/java-ats-only.tsh)
- [构建与测试工作流](.github/workflows/build.yml)
- [GitHub Packages 发布工作流](.github/workflows/publish-packages.yml)

## 许可证

许可证文本及仓库适用的授权条款请查看 [LICENSE](LICENSE)。
