<div align="center">

# Android Windbell Treesitter

**Android Java bindings for [Tree-sitter](https://tree-sitter.github.io/tree-sitter/)**

为 Android 提供 Tree-sitter 原生解析能力与多语言语法模块，适用于代码编辑器、语法分析和开发工具。

[![Maven Central](https://img.shields.io/maven-central/v/io.github.msmt2018/android-windchime-tree-sitter-highlight?label=Maven%20Central&logo=apachemaven)](https://central.sonatype.com/)
[![License](https://img.shields.io/badge/license-LGPL--2.1-blue.svg)](LICENSE)
[![GitHub](https://img.shields.io/badge/GitHub-Repository-181717?logo=github)](https://github.com/msmt2018/Android-windChime-tree-sitter-highlight)

</div>

---

## ✨ 项目简介

**Android Windbell Treesitter** 是面向 Android 的 Tree-sitter Java 绑定项目，提供核心解析库及可独立引入的语言语法模块。

- 📦 **Maven Central** — 通过 Gradle 依赖快速集成。
- 🧩 **模块化语法** — 按需添加语言，避免引入不需要的语法模块。
- ⚙️ **原生解析** — 通过 JNI 调用 Tree-sitter。
- 🛠️ **适用于开发工具** — 可用于代码编辑器、语法树分析、代码结构识别等场景。

> 本项目提供 Tree-sitter 的 Android 绑定与语法模块；语法高亮的 UI、主题映射和编辑器交互由上层应用实现。

## 🚀 快速接入

### 1. 配置 Maven Central

确保项目仓库包含 `mavenCentral()`。通常在 `settings.gradle.kts` 中配置：

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
```

### 2. 添加依赖

在应用模块的 `build.gradle.kts` 中添加核心库和需要的语法模块。

```kotlin
dependencies {
    // 核心 Java / JNI 绑定
    implementation("io.github.msmt2018:android-tree-sitter:<version>")

    // 按需添加语法模块（示例）
    implementation("io.github.msmt2018:tree-sitter-java:<version>")
    implementation("io.github.msmt2018:tree-sitter-kotlin:<version>")
    implementation("io.github.msmt2018:tree-sitter-json:<version>")
}
```

Groovy DSL (`build.gradle`)：

```groovy
dependencies {
    implementation 'io.github.msmt2018:android-tree-sitter:<version>'
    implementation 'io.github.msmt2018:tree-sitter-java:<version>'
    implementation 'io.github.msmt2018:tree-sitter-kotlin:<version>'
    implementation 'io.github.msmt2018:tree-sitter-json:<version>'
}
```

> **版本提示：** 将 `<version>` 替换为 Maven Central 中已发布的版本。核心库与语法模块应优先使用相同版本。上面的坐标示例按项目命名规则书写；发布前请以实际已发布的 artifact 名称为准。

### 3. 初始化原生库

在使用解析器之前加载 native library，例如在 `Application.onCreate()` 中：

```java
import com.zerostudio.treesitter.TreeSitter;

public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        TreeSitter.loadLibrary();
    }
}
```

如果你的应用中使用的初始化类或包路径与示例不同，请以当前发布版本中的 API 为准。

---

## 🧪 使用示例

下面是核心 API 的基本使用流程示意。请根据当前版本的实际类名和方法签名调整语言绑定类。

```java
// 1. 创建解析器
try (TSParser parser = TSParser.create()) {
    // 2. 设置语言
    parser.setLanguage(language);

    // 3. 解析源码
    TSTree tree = parser.parseString(null, sourceCode);

    // 4. 使用语法树
    TSNode rootNode = tree.getRootNode();
    // 读取节点类型、范围、子节点等信息

    // 5. 释放语法树资源
    tree.close();
}
```

**注意：** 上述代码用于展示解析流程。请以你使用的版本中 `TSParser`、`TSLanguage`、`TSTree` 的具体 API 为准，并及时释放 native 资源。

---

## 🌐 语法目录与上游仓库

这里列出的项目均来自仓库 `.gitmodules` 中配置的 grammar submodule。每一行都提供三项信息：

- **语言 / Grammar**：语法名称。
- **项目内目录**：点击可查看本仓库对应的语法目录。
- **上游语法仓库**：点击进入语法作者或维护组织的原始 GitHub 仓库，查看源码、许可证、更新记录与使用说明。

> **注意：** 配置为 Git submodule 不等于已经发布到 Maven Central。Maven 依赖请以实际发布的 artifact 为准；同一语言也可能有不同上游实现。Markdown 的目录在当前仓库中拼写为 `grammars/markdwon`，这里按仓库现有路径保留。

<details open>
<summary><strong>🧩 全部语法模块（点击展开 / 收起）</strong></summary>

| 语言 / Grammar | 项目内目录 | 上游语法仓库 |
|---|---|---|
| **JSON** | [`grammars/json`](grammars/json) | [tree-sitter](https://github.com/tree-sitter/tree-sitter-json) |
| **Kotlin** | [`grammars/kotlin`](grammars/kotlin) | [fwcd](https://github.com/fwcd/tree-sitter-kotlin) |
| **Logs** | [`grammars/log`](grammars/log) | [AndroidIDEOfficial](https://github.com/AndroidIDEOfficial/tree-sitter-log) |
| **XML** | [`grammars/xml`](grammars/xml) | [WillBooster](https://github.com/WillBooster/tree-sitter-xml) |
| **iRules** | [`grammars/irules`](grammars/irules) | [dekobon](https://github.com/dekobon/tree-sitter-irules) |
| **Properties** | [`grammars/properties`](grammars/properties) | [tree-sitter-grammars](https://github.com/tree-sitter-grammars/tree-sitter-properties) |
| **C** | [`grammars/c`](grammars/c) | [tree-sitter](https://github.com/tree-sitter/tree-sitter-c) |
| **Groovy** | [`grammars/groovy`](grammars/groovy) | [dekobon](https://github.com/dekobon/tree-sitter-groovy) |
| **Java** | [`grammars/java`](grammars/java) | [WillBooster](https://github.com/WillBooster/tree-sitter-java) |
| **AIDL** | [`grammars/aidl`](grammars/aidl) | [amaanq](https://github.com/amaanq/tree-sitter-aidl) |
| **Python** | [`grammars/python`](grammars/python) | [tree-sitter](https://github.com/tree-sitter/tree-sitter-python) |
| **C++** | [`grammars/cpp`](grammars/cpp) | [tree-sitter](https://github.com/tree-sitter/tree-sitter-cpp) |
| **C#** | [`grammars/c_sharp`](grammars/c_sharp) | [WillBooster](https://github.com/WillBooster/tree-sitter-c-sharp) |
| **Bash** | [`grammars/bash`](grammars/bash) | [tree-sitter](https://github.com/tree-sitter/tree-sitter-bash) |
| **Clojure** | [`grammars/clojure`](grammars/clojure) | [kodine-ai](https://github.com/kodine-ai/tree-sitter-clojure) |
| **SCSS** | [`grammars/scss`](grammars/scss) | [simeonoff](https://github.com/simeonoff/tree-sitter-scss) |
| **Dockerfile** | [`grammars/dockerfile`](grammars/dockerfile) | [camdencheek](https://github.com/camdencheek/tree-sitter-dockerfile) |
| **Containerfile** | [`grammars/containerfile`](grammars/containerfile) | [wharflab](https://github.com/wharflab/tree-sitter-containerfile) |
| **Diff** | [`grammars/diff`](grammars/diff) | [tree-sitter-grammars](https://github.com/tree-sitter-grammars/tree-sitter-diff) |
| **Elixir** | [`grammars/elixir`](grammars/elixir) | [elixir-lang](https://github.com/elixir-lang/tree-sitter-elixir) |
| **Elm** | [`grammars/elm`](grammars/elm) | [elm-tooling](https://github.com/elm-tooling/tree-sitter-elm) |
| **Go** | [`grammars/go`](grammars/go) | [tree-sitter](https://github.com/tree-sitter/tree-sitter-go) |
| **Go Modules** | [`grammars/gomod`](grammars/gomod) | [camdencheek](https://github.com/camdencheek/tree-sitter-go-mod) |
| **Haskell** | [`grammars/haskell`](grammars/haskell) | [tree-sitter](https://github.com/tree-sitter/tree-sitter-haskell) |
| **HCL** | [`grammars/hcl`](grammars/hcl) | [tree-sitter-grammars](https://github.com/tree-sitter-grammars/tree-sitter-hcl) |
| **HTML** | [`grammars/html`](grammars/html) | [tree-sitter](https://github.com/tree-sitter/tree-sitter-html) |
| **JavaScript** | [`grammars/javascript`](grammars/javascript) | [tree-sitter](https://github.com/tree-sitter/tree-sitter-javascript) |
| **JSDoc** | [`grammars/jsdoc`](grammars/jsdoc) | [tree-sitter](https://github.com/tree-sitter/tree-sitter-jsdoc) |
| **Julia** | [`grammars/julia`](grammars/julia) | [tree-sitter](https://github.com/tree-sitter/tree-sitter-julia) |
| **LaTeX** | [`grammars/latex`](grammars/latex) | [latex-lsp](https://github.com/latex-lsp/tree-sitter-latex) |
| **Lua** | [`grammars/lua`](grammars/lua) | [Azganoth](https://github.com/Azganoth/tree-sitter-lua) |
| **Markdown** | [`grammars/markdwon`](grammars/markdwon) | [tree-sitter-grammars](https://github.com/tree-sitter-grammars/tree-sitter-markdown) |
| **OCaml** | [`grammars/ocaml`](grammars/ocaml) | [msmt2018](https://github.com/msmt2018/tree-sitter-ocaml) |
| **Perl** | [`grammars/perl`](grammars/perl) | [ganezdragon](https://github.com/ganezdragon/tree-sitter-perl) |
| **PHP** | [`grammars/php`](grammars/php) | [tree-sitter](https://github.com/tree-sitter/tree-sitter-php) |
| **Pkl** | [`grammars/pkl`](grammars/pkl) | [apple](https://github.com/apple/tree-sitter-pkl) |
| **Ruby** | [`grammars/ruby`](grammars/ruby) | [tree-sitter](https://github.com/tree-sitter/tree-sitter-ruby) |
| **Scala** | [`grammars/scala`](grammars/scala) | [tree-sitter](https://github.com/tree-sitter/tree-sitter-scala) |
| **SQL** | [`grammars/sql`](grammars/sql) | [DerekStride](https://github.com/DerekStride/tree-sitter-sql) |
| **SSH Client Config** | [`grammars/ssh`](grammars/ssh) | [metio](https://github.com/metio/tree-sitter-ssh-client-config) |
| **Swift** | [`grammars/swift`](grammars/swift) | [alex-pinkus](https://github.com/alex-pinkus/tree-sitter-swift) |
| **TOML** | [`grammars/toml`](grammars/toml) | [konomanoasa](https://github.com/konomanoasa/tree-sitter-toml) |
| **Tree-sitter Query** | [`grammars/query`](grammars/query) | [tree-sitter-grammars](https://github.com/tree-sitter-grammars/tree-sitter-query) |
| **TypeScript / TSX** | [`grammars/typescript`](grammars/typescript) | [tree-sitter](https://github.com/tree-sitter/tree-sitter-typescript) |
| **Verilog** | [`grammars/verilog`](grammars/verilog) | [tree-sitter](https://github.com/tree-sitter/tree-sitter-verilog) |
| **Zig** | [`grammars/zig`](grammars/zig) | [maxxnino](https://github.com/maxxnino/tree-sitter-zig) |
| **CMake** | [`grammars/cmake`](grammars/cmake) | [uyha](https://github.com/uyha/tree-sitter-cmake) |
| **YAML** | [`grammars/yaml`](grammars/yaml) | [konomanoasa](https://github.com/konomanoasa/tree-sitter-yaml) |
| **Smali** | [`grammars/smali`](grammars/smali) | [msmt2018](https://github.com/msmt2018/Tree-Sitter-smali) |
| **Dart** | [`grammars/dart`](grammars/dart) | [WillBooster](https://github.com/WillBooster/tree-sitter-dart) |
| **Assembly** | [`grammars/asm`](grammars/asm) | [RubixDev](https://github.com/RubixDev/tree-sitter-asm) |
| **ARM64 Assembly** | [`grammars/arm64`](grammars/arm64) | [PhilippReinke](https://github.com/PhilippReinke/tree-sitter-arm64) |
| **LLVM IR** | [`grammars/llvm`](grammars/llvm) | [benwilliamgraham](https://github.com/benwilliamgraham/tree-sitter-llvm) |
| **LLVM MIR** | [`grammars/llvm_mir`](grammars/llvm_mir) | [Flakebi](https://github.com/Flakebi/tree-sitter-llvm-mir) |
| **YARA** | [`grammars/yara`](grammars/yara) | [pop-ecx](https://github.com/pop-ecx/tree-sitter-yara) |
| **Radare2 Shell** | [`grammars/radare2`](grammars/radare2) | [ret2libc](https://github.com/ret2libc/radare2-shell-parser) |
| **Radare2 Commands** | [`grammars/r2cmd`](grammars/r2cmd) | [ret2libc](https://github.com/ret2libc/radare2-shell-parser) |
| **Disassembly** | [`grammars/disassembly`](grammars/disassembly) | [ColinKennedy](https://github.com/ColinKennedy/tree-sitter-disassembly) |
| **Rust** | [`grammars/rust`](grammars/rust) | [tree-sitter](https://github.com/tree-sitter/tree-sitter-rust) |
| **F#** | [`grammars/fsharp`](grammars/fsharp) | [MangelMaxime](https://github.com/MangelMaxime/tree-sitter-fsharp) |
| **Erlang** | [`grammars/erlang`](grammars/erlang) | [WhatsApp](https://github.com/WhatsApp/tree-sitter-erlang) |
| **Nim** | [`grammars/nim`](grammars/nim) | [alaviss](https://github.com/alaviss/tree-sitter-nim) |
| **Crystal** | [`grammars/crystal`](grammars/crystal) | [crystal-lang-tools](https://github.com/crystal-lang-tools/tree-sitter-crystal) |
| **V** | [`grammars/v`](grammars/v) | [undivisible](https://github.com/undivisible/tree-sitter-v) |
| **D** | [`grammars/d`](grammars/d) | [gdamore](https://github.com/gdamore/tree-sitter-d) |
| **Fortran** | [`grammars/fortran`](grammars/fortran) | [stadelmanma](https://github.com/stadelmanma/tree-sitter-fortran) |
| **COBOL** | [`grammars/cobol`](grammars/cobol) | [yutaro-sakamoto](https://github.com/yutaro-sakamoto/tree-sitter-cobol) |
| **GraphQL** | [`grammars/graphql`](grammars/graphql) | [joowani](https://github.com/joowani/tree-sitter-graphql) |
| **Svelte** | [`grammars/svelte`](grammars/svelte) | [tree-sitter-grammars](https://github.com/tree-sitter-grammars/tree-sitter-svelte) |
| **Vue** | [`grammars/vue`](grammars/vue) | [kodine-ai](https://github.com/kodine-ai/tree-sitter-vue) |
| **Astro** | [`grammars/astro`](grammars/astro) | [alexandreteles](https://github.com/alexandreteles/tree-sitter-astro) |
| **Twig** | [`grammars/twig`](grammars/twig) | [gbprod](https://github.com/gbprod/tree-sitter-twig) |
| **Jinja2** | [`grammars/jinja2`](grammars/jinja2) | [geigerzaehler](https://github.com/geigerzaehler/tree-sitter-jinja2) |
| **Handlebars** | [`grammars/handlebars`](grammars/handlebars) | [bennypowers](https://github.com/bennypowers/tree-sitter-handlebars) |
| **Pug** | [`grammars/pug`](grammars/pug) | [codepen](https://github.com/codepen/tree-sitter-pug) |
| **Prisma** | [`grammars/prisma`](grammars/prisma) | [victorhqc](https://github.com/victorhqc/tree-sitter-prisma) |
| **HEEx** | [`grammars/heex`](grammars/heex) | [phoenixframework](https://github.com/phoenixframework/tree-sitter-heex) |
| **CSV** | [`grammars/csv`](grammars/csv) | [tree-sitter-grammars](https://github.com/tree-sitter-grammars/tree-sitter-csv) |
| **INI** | [`grammars/ini`](grammars/ini) | [justinmk](https://github.com/justinmk/tree-sitter-ini) |
| **Org Mode** | [`grammars/org`](grammars/org) | [nvim-orgmode](https://github.com/nvim-orgmode/tree-sitter-org) |
| **reStructuredText** | [`grammars/rst`](grammars/rst) | [stsewd](https://github.com/stsewd/tree-sitter-rst) |
| **AsciiDoc** | [`grammars/asciidoc`](grammars/asciidoc) | [cathaysia](https://github.com/cathaysia/tree-sitter-asciidoc) |
| **RON** | [`grammars/ron`](grammars/ron) | [zee-editor](https://github.com/zee-editor/tree-sitter-ron) |
| **Dhall** | [`grammars/dhall`](grammars/dhall) | [waves](https://github.com/waves/tree-sitter-dhall) |
| **Nix** | [`grammars/nix`](grammars/nix) | [nix-community](https://github.com/nix-community/tree-sitter-nix) |
| **KDL** | [`grammars/kdl`](grammars/kdl) | [tree-sitter-grammars](https://github.com/tree-sitter-grammars/tree-sitter-kdl) |
| **BibTeX** | [`grammars/bibtex`](grammars/bibtex) | [latex-lsp](https://github.com/latex-lsp/tree-sitter-bibtex) |
| **Protocol Buffers (proto)** | [`grammars/proto`](grammars/proto) | [coder3101](https://github.com/coder3101/tree-sitter-proto) |
| **Protocol Buffers** | [`grammars/protobuf`](grammars/protobuf) | [gortexhq](https://github.com/gortexhq/tree-sitter-protobuf) |
| **Make** | [`grammars/make`](grammars/make) | [make-language-server](https://github.com/make-language-server/tree-sitter-make) |
| **Nginx** | [`grammars/nginx`](grammars/nginx) | [opa-oz](https://github.com/opa-oz/tree-sitter-nginx) |
| **Zsh** | [`grammars/zsh`](grammars/zsh) | [zchee](https://github.com/zchee/tree-sitter-zsh) |
| **Gitignore** | [`grammars/gitignore`](grammars/gitignore) | [konomanoasa](https://github.com/konomanoasa/tree-sitter-gitignore) |
| **Git Rebase** | [`grammars/git_rebase`](grammars/git_rebase) | [the-mikedavis](https://github.com/the-mikedavis/tree-sitter-git-rebase) |
| **Git Commit** | [`grammars/gitcommit`](grammars/gitcommit) | [gbprod](https://github.com/gbprod/tree-sitter-gitcommit) |
| **Regex** | [`grammars/regex`](grammars/regex) | [konomanoasa](https://github.com/konomanoasa/tree-sitter-regex) |
| **printf** | [`grammars/printf`](grammars/printf) | [ozacod](https://github.com/ozacod/tree-sitter-printf) |
| **Requirements** | [`grammars/requirements`](grammars/requirements) | [tree-sitter-grammars](https://github.com/tree-sitter-grammars/tree-sitter-requirements) |
| **Mermaid** | [`grammars/mermaid`](grammars/mermaid) | [pappasam](https://github.com/pappasam/tree-sitter-mermaid) |
| **Thrift** | [`grammars/thrift`](grammars/thrift) | [tree-sitter-grammars](https://github.com/tree-sitter-grammars/tree-sitter-thrift) |
| **Git Attributes** | [`grammars/gitattributes`](grammars/gitattributes) | [tree-sitter-grammars](https://github.com/tree-sitter-grammars/tree-sitter-gitattributes) |
| **GDB** | [`grammars/gdb`](grammars/gdb) | [Shane-Gadsby](https://github.com/Shane-Gadsby/godot-gdb-tree-sitter) |

</details>


### Maven 依赖说明

已发布的语法模块通常遵循以下命名形式：

```kotlin
dependencies {
    implementation("io.github.msmt2018:android-tree-sitter:<version>")
    implementation("io.github.msmt2018:tree-sitter-java:<version>")
    implementation("io.github.msmt2018:tree-sitter-kotlin:<version>")
}
```

只有已在 Maven Central 发布的模块才能直接通过该方式引用。其余 grammar 可按项目构建方式编译，或使用项目支持的外部 native grammar 加载方式。请勿将上表理解为所有语法均已发布。

---

## 🔧 从源码构建

### 环境要求

| 工具 | 要求 |
| --- | --- |
| JDK | JDK 11-17 或更高版本；`JAVA_HOME` 应指向包含 JNI 头文件的 JDK |
| Android NDK | r24 | 使用项目 Gradle / CMake 配置所要求的版本 |
| CMake | 必需 |
| Ninja | 必需 |
| Node.js（Version：20） + npm | 部分 grammar 需要安装 Node 依赖 |
| Rust + Cargo | Cargo / Rust 工具链，按项目要求安装 |
| tree-sitter CLI | 用于构建 grammar |

Linux 环境通常更适合执行完整的 native 构建。

Ubuntu / Debian 可先安装常用系统依赖：

```bash
sudo apt update
sudo apt install build-essential nodejs npm cmake ninja-build git
```

安装 Rust 工具链：<https://www.rust-lang.org/tools/install>

### 1. 获取源码

```bash
git clone --recurse-submodules https://github.com/msmt2018/Android-windChime-tree-sitter-highlight.git
cd Android-windChime-tree-sitter-highlight
```

如果已经克隆项目但没有初始化子模块：

```bash
git submodule update --init --recursive
```

### 2. 安装 grammar 依赖

部分 grammar 可能需要单独安装 npm 依赖。例如：

```bash
cd grammars/cpp
npm install
cd ../..
```

只有在对应 grammar 存在 `package.json` 且构建需要时才执行此步骤。

### 3. 构建

构建整个项目：

```bash
./gradlew build
```

仅构建指定模块时，可先查看 Gradle 任务：

```bash
./gradlew tasks
```

如果项目提供 `buildForHost` 任务，可在相应子项目执行它以构建 host 版本。具体任务名称以当前分支的 Gradle 配置为准。

### 4. 使用预构建的 tree-sitter CLI（可选）

如果已经通过系统包管理器、npm 或 Cargo 安装 `tree-sitter` CLI，并且该命令已加入 `PATH`，可尝试设置：

```bash
export TS_CLI_BUILD_FROM_SOURCE=false
```

若构建脚本仍尝试从源码编译 CLI，请检查当前分支的构建逻辑及环境变量支持情况。

---

## 🧩 添加新的语法

1. 将 grammar 仓库作为 Git submodule 添加到 `grammars/<language>`。
2. 在项目的 grammar 配置清单中登记该语言。
3. 按项目现有模块生成规则生成或配置 Gradle 模块。
4. 检查 JNI 绑定、CMake 配置及上游 grammar 的构建依赖。
5. 执行构建与测试，并确认目标 ABI 的 native library 已正确打包。

示例：

```bash
git submodule add <grammar-repository-url> grammars/<language>
git submodule update --init --recursive
```

请参考仓库中已有 grammar 的目录结构和构建脚本，保持模块命名一致。

---

## ⚠️ 外部语法与 native 资源

- 优先使用随应用打包的语法库，避免运行时加载带来的路径、ABI 和生命周期问题。
- 如果使用 `TSLanguage.loadLanguage(...)` 加载外部 `.so`，请确认当前版本支持该 API，并遵循对应版本的关闭规则。
- 对 parser、tree、language 等 native 资源，应按 API 要求及时释放，避免 native 内存或动态库句柄泄漏。
- 应用需要覆盖哪些 ABI，取决于所使用的预编译库或 native 构建产物。

---

## 📄 许可证

本项目的许可证以仓库中的 [`LICENSE`](LICENSE) 文件为准。若当前分支沿用上游 android-tree-sitter 的 LGPL-2.1 许可，请阅读完整许可证及其对分发、修改和链接方式的要求。

- [Tree-sitter 官方文档](https://tree-sitter.github.io/tree-sitter/)
- [项目源码](https://github.com/msmt2018/Android-windChime-tree-sitter-highlight)
- [Maven Central](https://central.sonatype.com/)

---

<div align="center">

Made for Android developers · **Android Windbell Treesitter**

</div>
