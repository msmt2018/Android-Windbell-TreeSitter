<!-- 项目标识置顶并居中 -->
<p align="center">
  <img src="logo.png" alt="Android Windbell Tree-sitter Logo" width="180">
</p>

<h1 align="center">Android Windbell Tree-sitter</h1>

<p align="center">
  <strong>面向 Android 的 Tree-sitter 原生解析、Windbell TSH 语法高亮引擎与多语言 Grammar 模块体系。</strong>
</p>

<p align="center">
  <a href="https://github.com/msmt2018/Android-Windbell-TreeSitter/actions/workflows/build.yml"><img src="https://github.com/msmt2018/Android-Windbell-TreeSitter/actions/workflows/build.yml/badge.svg" alt="Build and test"></a>
  <a href="https://jitpack.io/#msmt2018/Android-Windbell-TreeSitter"><img src="https://jitpack.io/v/msmt2018/Android-Windbell-TreeSitter.svg" alt="JitPack"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-AGPL--3.0--only-blue.svg" alt="AGPL-3.0-only"></a>
</p>

<p align="center">
  <a href="README.md">English</a> ·
  <a href="https://tree-sitter.github.io/tree-sitter/">Tree-sitter 官方文档</a> ·
  <a href="https://jitpack.io/#msmt2018/Android-Windbell-TreeSitter">JitPack</a>
</p>

---

## 项目简介

**Android Windbell Tree-sitter** 是面向 Android 平台的 Tree-sitter 集成与语言工具链项目。项目包含 JNI 原生绑定、多语言 Grammar 子模块、Piece Tree 文本缓冲区、编辑器集成，以及 **Windbell Tree-sitter Highlighting（TSH）语法高亮引擎**。

项目主要面向 Android 代码编辑器、源码查看器、IDE、语言感知型开发工具，以及需要源代码结构分析能力的应用。

### 核心组成

- **Android Tree-sitter Binding**：通过 JNI 将 Tree-sitter 原生 C 运行时能力提供给 Java/Kotlin。
- **Grammar 模块体系**：各语言 parser 以 Git submodule 维护并构建为 Native 库，应用可按需集成所需语法。
- **Windbell TSH 引擎**：编译并执行 `.tsh` 语言高亮配置。语法分析主链路基于 Tree-sitter AST Selector（ATS）和原生 Tree-sitter Query；Oniguruma Regex 是可选的补充匹配后端。
- **Editor 集成**：仓库包含编辑器与 Piece Tree 模块，以及将 TSH RenderPlan 转换为编辑器样式的桥接实现。
- **Native / Host 开发能力**：Gradle、CMake 和 Host JNI 构建支持 Android 构建与本地 Native 测试。

TSH 不使用 Monarch 高亮管线。Tree-sitter Grammar 负责生成语法结构；TSH 根据 ATS / Query 规则选择结构节点，也可以执行可选 Regex 规则，再将捕获范围和样式结果交给编辑器渲染层。文本展示、主题应用和最终绘制由上层编辑器完成。

### 仓库结构

| 路径 | 用途 |
| --- | --- |
| `android-tree-sitter/` | Android Java/JNI Binding 与 TSH 引擎实现 |
| `android-tree-sitter/src/main/cpp/` | Tree-sitter Native Runtime、JNI 桥接、TSH Native 实现和 Oniguruma |
| `tree-sitter-lib/` | Tree-sitter 上游源码子模块 |
| `grammars/` | 各语言 Grammar 源码子模块及 `grammars.json` 清单 |
| `app/` | Android 示例 / 集成应用与 TSH 配置样例 |
| `editor/` | 编辑器集成与语法高亮桥接 |
| `piecetable/` | Piece Tree 文本缓冲区模块 |
| `generate_grammars.py` | 根据 Git 子模块元数据和 parser 源码生成 Grammar 清单 |
| `.github/workflows/build.yml` | CI 构建、Native 测试、JVM 测试与 Android 模拟器测试 |

## 快速开始：在 Android 中使用 Tree-sitter

### 1. 配置 Maven 仓库

在应用工程的 `settings.gradle.kts` 中添加 JitPack 仓库：

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

如果项目已经在 `settings.gradle` 集中管理仓库，请直接在集中仓库配置中添加，不要重复在各个 library module 中配置。

### 2. 添加核心 Binding 与所需 Grammar

JitPack 对 Gradle 多模块 GitHub 项目采用的标准依赖格式为：

```text
com.github.<owner>.<repository>:<module>:<tag>
```

本仓库模块名称与 Gradle project name 对应，例如：

```kotlin
dependencies {
    implementation("com.github.msmt2018.Android-Windbell-TreeSitter:android-tree-sitter:<tag>")
    implementation("com.github.msmt2018.Android-Windbell-TreeSitter:tree-sitter-java:<tag>")
}
```

将 `<tag>` 替换为仓库中真实存在的 Git Tag。首次请求时，JitPack 会构建对应源码版本。请在 [JitPack 项目页面](https://jitpack.io/#msmt2018/Android-Windbell-TreeSitter) 检查构建日志和 Modules 列表，确认对应 Tag 与模块产物可用。

**注意发布状态：** 某个 Grammar 存在于 `grammars/`，不代表它已经发布为 Maven AAR。当前 Gradle 发布配置明确选择的模块数量有限，添加依赖前应先确认 JitPack 实际构建结果与模块列表。不能根据 Grammar 目录列表推断其已经发布。

### 3. 加载 Native Runtime 并解析源码

创建 Parser 之前必须加载核心 Native 库。Grammar 模块提供对应语言的生成绑定类。

Java 示例：

```java
import com.zerostudio.idea.treesitter.TreeSitter;
import com.zerostudio.idea.treesitter.TSParser;
import com.zerostudio.idea.treesitter.TSTree;
import com.zerostudio.idea.treesitter.TSNode;
import com.zerostudio.idea.treesitter.java.TSLanguageJava;

public final class ParseExample {
    public static void parse(String source) {
        TreeSitter.loadLibrary();

        try (TSParser parser = TSParser.create()) {
            parser.setLanguage(TSLanguageJava.getInstance());

            try (TSTree tree = parser.parseString(source)) {
                if (tree == null) {
                    throw new IllegalStateException("Tree-sitter parsing failed");
                }

                TSNode root = tree.getRootNode();
                String rootType = root.getType();
                // 在这里读取节点类型、范围、字段和子节点。
            }
        }
    }
}
```

基本生命周期为：加载核心 Native 库、创建 Parser、设置 Language、解析源码、读取根节点并释放 Native 资源。Parser / Tree 的创建和释放应遵循 API 的资源所有权规则。不同 Grammar 的生成绑定包名与具体 artifact 有关，请以实际引入的模块为准。

### 4. 使用 TSH 进行语法高亮

示例应用的 `app/src/main/assets/tsh/` 下包含可参考的 `.tsh` 配置，例如 Java baseline、ATS-only、Regex-only 和 Hybrid 配置。建议从这些配置开始理解语法和规则结构。

典型集成流程：

1. 加载 Tree-sitter 核心 Native Runtime，并注册或加载目标语言 Grammar。
2. 加载并编译该语言对应的 `.tsh` 配置。
3. 通过 TSH 文档 / 高亮 API 提供当前文档内容或有界文本窗口。
4. 执行配置中的 ATS / Native Query 规则，以及可选的 Oniguruma Regex 规则。
5. 将返回的 capture 范围和 style 信息转换为编辑器渲染模型。
6. 安装 RenderPlan 时保持 document identity 与 document revision 一致；编辑后不能继续安装旧版本的 RenderPlan。

TSH 公共 API 位于 `com.zerostudio.idea.treesitter.tsh` 及其 API 子包。当前可用的具体入口以示例应用和测试源码为准。不要将内部 Native 实现类视为稳定公共 API。

## Windbell TSH 引擎

TSH 是围绕 Tree-sitter 语法树构建的语言高亮配置与执行引擎，不是单纯依靠文本扫描的 Tokenizer 管线。

### 分析后端

- **ATS（AST Selector）**：依据语法节点结构、父子/祖先/兄弟关系及节点属性进行选择。
- **Native Tree-sitter Query**：针对指定 Grammar 编译 Tree-sitter Query 表达式并返回捕获范围。
- **可选 Regex**：基于 Oniguruma 的正则匹配可补充 AST / Query 不适合表达的文本模式。
- **Hybrid 配置**：允许 ATS / Query 与 Regex 规则组合启用。示例 assets 中包含隔离配置，可分别验证各后端。

### 配置与渲染职责

`.tsh` 配置描述语言元数据、编辑器元数据、语义样式、颜色方案和高亮规则。TSH 引擎负责配置编译与规则执行，并输出 capture / style 结果；编辑器集成层负责把结果映射为真实的文本 Span 或 Render Instruction。

自定义接入时应保留 API 提供的坐标系和文档版本信息。尤其不能将旧文档版本生成的 RenderPlan 安装到新版本文档。

### 大文本处理设计

代码库包含 Parser Binding 的有界 UTF-16LE 输入窗口、Piece Tree 编辑器缓冲区、范围化高亮 API，以及 capture / render 缓存。窗口化输入用于减少临时整文档复制；它不代表 Tree-sitter 只构建局部 AST。完整解析耗时、Query / capture 耗时和编辑器绘制耗时是不同环节，应分别测量。

## 已有 Grammar Languages

以下语言目录已纳入仓库 Grammar 目录清单。括号中显示的是仓库实际模块目录名。该目录清单代表源码集成情况，**不代表每种语言都已经发布 Maven artifact**。

| 语言 | Grammar 模块 |
| --- | --- |
| AIDL | [aidl](grammars/aidl) |
| ARM64 | [arm64](grammars/arm64) |
| AsciiDoc | [asciidoc](grammars/asciidoc) |
| ASM | [asm](grammars/asm) |
| Bash | [bash](grammars/bash) |
| C | [c](grammars/c) |
| CMake | [cmake](grammars/cmake) |
| Containerfile | [containerfile](grammars/containerfile) |
| C++ | [cpp](grammars/cpp) |
| CSharp | [csharp](grammars/csharp) |
| CSV | [csv](grammars/csv) |
| Dart | [dart](grammars/dart) |
| Diff | [diff](grammars/diff) |
| Disassembly | [disassembly](grammars/disassembly) |
| Dockerfile | [dockerfile](grammars/dockerfile) |
| GDB | [gdb](grammars/gdb) |
| Git Rebase | [git_rebase](grammars/git_rebase) |
| Git Attributes | [gitattributes](grammars/gitattributes) |
| Git Commit | [gitcommit](grammars/gitcommit) |
| Git Ignore | [gitignore](grammars/gitignore) |
| Go | [go](grammars/go) |
| Go Modules | [gomod](grammars/gomod) |
| GraphQL | [graphql](grammars/graphql) |
| Groovy | [groovy](grammars/groovy) |
| INI | [ini](grammars/ini) |
| Java | [java](grammars/java) |
| JavaScript | [javascript](grammars/javascript) |
| JSDoc | [jsdoc](grammars/jsdoc) |
| JSON | [json](grammars/json) |
| Kotlin | [kotlin](grammars/kotlin) |
| LLVM | [llvm](grammars/llvm) |
| LLVM MIR | [llvm_mir](grammars/llvm_mir) |
| Logcat | [logcat](grammars/logcat) |
| Lua | [lua](grammars/lua) |
| Make | [make](grammars/make) |
| Markdown | [markdwon](grammars/markdwon) |
| PHP | [php](grammars/php) |
| Properties | [properties](grammars/properties) |
| Proto | [proto](grammars/proto) |
| Python | [python](grammars/python) |
| Regex | [regex](grammars/regex) |
| Requirements | [requirements](grammars/requirements) |
| reStructuredText | [rst](grammars/rst) |
| Ruby | [ruby](grammars/ruby) |
| Rust | [rust](grammars/rust) |
| SCSS | [scss](grammars/scss) |
| Smali | [smali](grammars/smali) |
| SQL | [sql](grammars/sql) |
| SSH | [ssh](grammars/ssh) |
| Starlark | [starlark](grammars/starlark) |
| Svelte | [svelte](grammars/svelte) |
| Swift | [swift](grammars/swift) |
| TOML | [toml](grammars/toml) |
| TypeScript | [typescript](grammars/typescript) |
| Vue | [vue](grammars/vue) |
| XML | [xml](grammars/xml) |
| YAML | [yaml](grammars/yaml) |
| Zsh | [zsh](grammars/zsh) |

## 构建环境

以下版本依据仓库 Gradle Version Catalog、Gradle Wrapper 和当前 GitHub Actions 构建工作流整理。需要复现 CI 时，建议保持工具版本一致。

| 工具 | 项目 / CI 版本 |
| --- | --- |
| JDK | 17（CI 使用 Temurin） |
| Gradle | Wrapper 8.14.8 |
| Android Gradle Plugin | 8.13.2 |
| Kotlin Gradle Plugin | 2.4.21 |
| Android NDK | r29 / 29.0.14206865 |
| Android CMake | 3.31.6 |
| Android Build Tools | 35.0.0 |
| Python | 3.12 |
| Node.js | 22.22.2 |
| npm | 随 Node.js 安装 |
| Bun | 由工作流安装 |
| Rust / Cargo | Native / Grammar 构建依赖 |
| Ninja | Native 构建工具 |
| Git | 必需，需支持递归 submodule |

当前 Android 构建配置使用 compile SDK 34、min SDK 21。Android Studio 中需要安装兼容的 Android SDK Platform，并通过 SDK Manager 安装上述指定版本的 NDK 和 CMake。

### Android Studio 环境配置

1. 安装 JDK 17（CI 使用 Temurin），并在 Android Studio 的 **Gradle JDK** 中选择该 JDK。
2. 使用 Android Studio 打开仓库根目录，不要只打开某一个 Module。
3. 进入 **Tools → SDK Manager → SDK Platforms**，安装 Android API 34。
4. 在 **SDK Tools** 中勾选 **Show Package Details**，安装：
   - Android SDK Build-Tools 35.0.0
   - NDK (Side by side) 29.0.14206865
   - CMake 3.31.6
   - Android SDK Command-line Tools（latest）
5. 接受 Android SDK Licenses。
6. 使用仓库自带的 Gradle Wrapper（8.14.8），不要用系统中其他版本的 Gradle 替代。
7. Gradle Sync / Build 前初始化 Git submodule，Grammar 源码和 Tree-sitter Runtime 均通过子模块管理。

建议环境变量：

- `JAVA_HOME`：JDK 17 安装目录。
- `ANDROID_SDK_ROOT`（或 `ANDROID_HOME`）：Android SDK 目录。
- `ANDROID_NDK_HOME`：`<ANDROID_SDK_ROOT>/ndk/29.0.14206865`。

## Linux 构建

CI 工作流运行于 Ubuntu，并安装以下系统依赖：

```bash
sudo apt-get update
sudo apt-get install -y --no-install-recommends \
  build-essential cargo nodejs cmake ninja-build
```

安装上表指定版本的 JDK、Android SDK、NDK 和 CMake；同时安装 Python 3.12、Node.js 22.22.2 与 Bun。然后克隆并初始化仓库：

```bash
git clone --recurse-submodules https://github.com/msmt2018/Android-Windbell-TreeSitter.git
cd Android-Windbell-TreeSitter
git submodule sync --recursive
git submodule update --init --recursive
```

对于声明了 `package.json` 的 Grammar，需要安装对应 npm 依赖。CI 会自动发现 package manifest、只安装 npm workspace 根目录、跳过由 workspace 管理的子包，并将并行安装数限制为 4。单独安装某个 Grammar 时，例如：

```bash
cd grammars/cpp
npm install --legacy-peer-deps --no-audit --no-fund
cd ../..
```

Java Grammar 由仓库脚本固定生成 ABI 15。如果 parser 源码尚未达到 ABI 15，手动 Native 构建前执行：

```bash
bash scripts/generate-java-grammar-abi15.sh
```

构建 Release APK 和调试符号：

```bash
./gradlew assembleRelease generateDebugSymbolsRelease --parallel --stacktrace
```

APK 输出目录：

```text
app/build/outputs/apk/release/
```

运行 JVM / Unit Tests，并编译 Android Instrumentation Test 源码：

```bash
./gradlew testDebug \
  :editor:compileDebugAndroidTestKotlin \
  :app:compileDebugAndroidTestKotlin \
  --continue --parallel --stacktrace
```

CI 还会构建 Host JNI Library、执行 Native TSH Cache Tests、在 ASan/UBSan 下运行大文件存储测试，并在 API 26、30、35 的 Android Emulator 上运行 Instrumentation Tests。

## Windows 构建

当前仓库已配置的 GitHub Actions 构建工作流以 Linux 为目标，尚不能据此宣称原生 Windows 构建已通过。Windows 用户最容易复现 CI 的方式是 **WSL2 + Ubuntu**，然后直接按照上面的 Linux 构建步骤操作。

如果需要原生 Windows 环境：

1. 安装 Android Studio、JDK 17、Android SDK Platform 34、Build-Tools 35.0.0、NDK 29.0.14206865 和 CMake 3.31.6。
2. 安装 Git for Windows（包含 Git Bash）、Python 3.12、Node.js 22.22.2、Bun、Rust/Cargo 和 Ninja。
3. 仓库的部分脚本使用 Bash，例如 `scripts/generate-java-grammar-abi15.sh`，需要通过 Git Bash 执行。部分 Grammar 构建脚本还可能依赖 MSYS2 工具（`bash`、`sed`、`find`、`make` / 编译器工具）。
4. 配置 `JAVA_HOME`、`ANDROID_SDK_ROOT` 和 `ANDROID_NDK_HOME`，并确保 JDK、SDK Command-line Tools、NDK、CMake、Ninja、Python、Node 与 Rust 命令均可从 PATH 访问。
5. 使用递归 submodule 克隆仓库，并按前文安装 Grammar npm 依赖。
6. 在 PowerShell 中使用仓库 Gradle Wrapper：

```powershell
.\gradlew.bat assembleRelease generateDebugSymbolsRelease --parallel --stacktrace
.\gradlew.bat testDebug --continue --parallel --stacktrace
```

如果 Grammar 辅助脚本需要 Bash，请通过 Git Bash 或 WSL 调用。原生 Windows 的兼容性还取决于各 Grammar 自身的构建脚本与 Native 依赖；遇到 shell / toolchain 差异时，建议切换到 WSL2。

## Grammar 清单生成器：`generate_grammars.py`

在仓库根目录完成 submodule 初始化后运行：

```bash
python generate_grammars.py
```

脚本生成文件为 `grammars/grammars.json`。它的目标是根据实际 Git 子模块与 parser 源码自动同步 Grammar 清单，避免手工维护条目。

### 脚本执行逻辑

1. 读取根目录 `.gitmodules`，提取所有注册在 `grammars/` 下的路径。
2. 将 Git submodule 路径与本地实际存在的 Grammar 目录求交集；未初始化的子模块不会被视为本地有效源码。
3. 递归搜索 Grammar 目录中的 `src/parser.c` 或 `src/parser.cc`。
4. 忽略隐藏路径，以及 `node_modules`、`.git`、`.github`、`build`、`target` 目录。
5. 尽可能从 parser 源码提取真实的 `tree_sitter_*` C 入口符号。
6. 自动识别 Composite Grammar：记录 `sub.paths` 和 `sub.entries`；普通单 Parser Grammar 则记录 `entry`。
7. 检测额外 Scanner 源文件（`scanner.c` / `scanner.cc`），并写入 `src.extra`。
8. 先写入临时 JSON 文件，完成后再替换为 `grammars/grammars.json`。

根目录 Gradle Settings 应用的 Dynamic Grammar Module Plugin 会消费生成的 Grammar 元数据，用于配置项目中的动态 Grammar 模块。

**该脚本不会**克隆 submodule、安装 npm 依赖、生成 parser 源码、编译 Native 库或发布 Maven artifact。这些步骤由独立的依赖安装与构建流程完成。

生成后检查变更：

```bash
git diff -- grammars/grammars.json
```

## 添加或更新 Grammar

1. 将上游 Grammar 仓库添加为 `grammars/<module-name>` 下的 Git submodule。
2. 初始化 / 更新该 submodule。
3. 确认 parser 源码存在（通常是 `src/parser.c` 或 `src/parser.cc`），并检查 Scanner 与 npm 构建依赖。
4. 执行 `python generate_grammars.py` 更新 `grammars/grammars.json`。
5. Gradle Sync 并构建目标模块。
6. 检查生成的 JNI Language Binding，以及目标 ABI 对应的 `.so` 是否正确打包。
7. 检查上游 Grammar 的许可证并保留其版权与许可声明。

示例：

```bash
git submodule add <upstream-grammar-url> grammars/<module-name>
git submodule update --init --recursive
python generate_grammars.py
```

## 外部 Native Grammar 加载

优先将 Grammar Native 库作为应用依赖打包。运行时加载外部 Native Grammar 时，需要确认 ABI 与库路径兼容，并遵守 Language Native Handle 生命周期。使用动态加载前，请先阅读当前 Binding 中的 `TSLanguage.loadLanguage(...)` 和 `TSLanguage.close()` 实现及 API 约束。

## 许可证与第三方声明

仓库根目录 `LICENSE` 使用 GNU Affero General Public License Version 3（AGPL-3.0-only）。AGPL 要求分发受覆盖作品时保留许可与版权声明，并履行对应源码提供义务；第 13 条还规定了修改版本通过网络向用户提供服务时的源码获取要求。

各 Grammar 目录来自不同上游作者维护的独立项目。Git submodule 不会自动改变上游项目的许可证。重新分发前应分别检查每个 Grammar 的许可证与版权声明；Tree-sitter、Oniguruma 等嵌套第三方组件也应遵守其各自许可要求。 另外，仅替换根目录 LICENSE 并不会自动改写已有源码文件头中的许可证声明或上游派生源码的许可信息；当前仓库仍有带旧 GPL/LGPL 声明的源码文件，Gradle 发布 POM 中的许可证元数据也需要进一步统一核对。因此，在完成源码文件与发布元数据的许可审计前，不能把仓库内所有代码和产物笼统声明为统一 AGPL-3.0-only。

- [仓库完整许可证](LICENSE)
- [GNU AGPL v3 官方文本](https://www.gnu.org/licenses/agpl-3.0.html)
- [JitPack 多模块构建文档](https://docs.jitpack.io/building/)
- [JitPack Android 文档](https://docs.jitpack.io/android/)
- [Tree-sitter 官方文档](https://tree-sitter.github.io/tree-sitter/)

---

<p align="center">
  Android Native Parsing · Tree-sitter AST · Windbell TSH · Modular Grammars
</p>
