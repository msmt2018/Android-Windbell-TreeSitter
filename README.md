<!-- Keep the project mark centered above the project title. -->
<p align="center">
  <img src="logo.png" alt="Android Windbell Tree-sitter logo" width="180">
</p>

<h1 align="center">Android Windbell Tree-sitter</h1>

<p align="center">
  <strong>Native Tree-sitter parsing, the Windbell TSH highlighting engine, and a modular grammar ecosystem for Android.</strong>
</p>

<p align="center">
  <a href="https://github.com/msmt2018/Android-Windbell-TreeSitter/actions/workflows/build.yml"><img src="https://github.com/msmt2018/Android-Windbell-TreeSitter/actions/workflows/build.yml/badge.svg" alt="Build and test"></a>
  <a href="https://jitpack.io/#msmt2018/Android-Windbell-TreeSitter"><img src="https://jitpack.io/v/msmt2018/Android-Windbell-TreeSitter.svg" alt="JitPack"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-AGPL--3.0--only-blue.svg" alt="AGPL-3.0-only"></a>
</p>

<p align="center">
  <a href="README_cn.md">简体中文</a> ·
  <a href="https://tree-sitter.github.io/tree-sitter/">Tree-sitter Documentation</a> ·
  <a href="https://jitpack.io/#msmt2018/Android-Windbell-TreeSitter">JitPack</a>
</p>

---

## Overview

Android Windbell Tree-sitter is an Android-focused Tree-sitter integration and language tooling project. It combines a JNI-based Android binding for the native Tree-sitter runtime, independently buildable grammar modules, a Piece Tree-backed editor integration, and the **Windbell Tree-sitter Highlighting (TSH)** engine.

The project is intended for Android code editors, source viewers, IDEs, language-aware developer tools, and applications that need structural source analysis.

### Main components

- **Android Tree-sitter binding** — Java/Kotlin APIs backed by the native Tree-sitter C runtime through JNI.
- **Grammar modules** — language parsers are maintained as Git submodules and built as native libraries; applications can include only the grammars they need.
- **Windbell TSH engine** — compiles and executes `.tsh` language highlighting configurations. Its syntax-analysis path is based on Tree-sitter AST selectors (ATS) and native Tree-sitter queries; Oniguruma Regex is an optional complementary matcher.
- **Editor integration** — the repository includes editor and Piece Tree modules, plus a RenderPlan-to-editor styling bridge.
- **Native and host development** — Gradle, CMake and host JNI targets support Android builds and local native tests.

TSH is not a Monarch-based pipeline. The grammar parser produces syntax structure; TSH rules select structural nodes and/or execute optional Regex rules, then expose style/capture results for the editor rendering layer. The editor remains responsible for presenting text and applying the returned style information.

### Repository layout

| Path | Purpose |
| --- | --- |
| `android-tree-sitter/` | Android Java/JNI binding and TSH engine implementation |
| `android-tree-sitter/src/main/cpp/` | Tree-sitter native runtime, JNI bridge, TSH native implementation and Oniguruma |
| `tree-sitter-lib/` | Upstream Tree-sitter source submodule |
| `grammars/` | Grammar source submodules and `grammars.json` manifest |
| `app/` | Android sample / integration application and TSH profiles |
| `editor/` | Editor integration and syntax-highlighting bridge |
| `piecetable/` | Piece Tree text-buffer module |
| `generate_grammars.py` | Generates the grammar manifest from Git submodule metadata and parser sources |
| `.github/workflows/build.yml` | CI build, native tests, JVM tests and Android emulator tests |

## Quick start: use Tree-sitter from Android

### 1. Add repositories

The project is a multi-module Gradle repository. For JitPack, add its Maven repository to the consuming application's `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

If the project already centralizes repositories in `settings.gradle`, add JitPack there rather than adding a repository to an individual library module.

### 2. Add the core binding and the required grammar

JitPack's documented coordinate convention for a multi-module GitHub project is:

```text
com.github.<owner>.<repository>:<module>:<tag>
```

For this repository, the module names follow the Gradle project names:

```kotlin
dependencies {
    implementation("com.github.msmt2018.Android-Windbell-TreeSitter:android-tree-sitter:<tag>")
    implementation("com.github.msmt2018.Android-Windbell-TreeSitter:tree-sitter-java:<tag>")
}
```

Replace `<tag>` with a Git tag that exists in this repository, for example a release tag. The first JitPack request builds the selected revision; inspect the [JitPack project page](https://jitpack.io/#msmt2018/Android-Windbell-TreeSitter) and its build log to confirm that the tag and module artifact are available.

**Publication status matters:** a grammar being present under `grammars/` does not mean that its AAR has been published. The current Gradle publication configuration explicitly selects a limited set of modules; check the JitPack module list/build result before adding a grammar artifact. Do not infer publication from the grammar catalogue.

### 3. Load the native runtime and parse source

The core library must be loaded before creating a parser. A grammar module provides its generated language binding.

Java example:

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
                // Inspect node types, ranges, fields and child nodes here.
            }
        }
    }
}
```

The example shows the basic lifecycle: load the core native library, create a parser, set a language, parse, inspect the root node, and close native resources. Keep the parser and tree lifecycle aligned with the API's ownership rules. The generated grammar binding package is language-specific; use the package shipped by the grammar artifact you selected.

### 4. Use TSH highlighting

The sample application contains working `.tsh` profiles under `app/src/main/assets/tsh/`, including Java baseline, ATS-only, Regex-only and Hybrid profiles. They are the best starting point for the configuration syntax and supported rule forms.

A typical integration flow is:

1. Load the core native runtime and register/load the selected Tree-sitter language.
2. Load and compile the language's `.tsh` configuration.
3. Supply the current document text or bounded text windows through the TSH document/highlighting APIs.
4. Execute the configured ATS / native query rules and optional Oniguruma Regex rules.
5. Convert the returned captures and style information into the editor's rendering model.
6. Keep document identity and document revision consistent when installing a RenderPlan; discard stale plans after edits.

The public TSH API is under `com.zerostudio.idea.treesitter.tsh` and its API subpackages. The sample app and tests demonstrate the current concrete entry points. Avoid treating internal native implementation classes as stable public APIs.

## Windbell TSH engine

TSH is a language-highlighting configuration and execution engine designed around Tree-sitter's syntax tree rather than a text-only tokenization pipeline.

### Analysis backends

- **ATS (AST selectors):** selects syntax nodes by structural relationships and node properties.
- **Native Tree-sitter queries:** compiles Tree-sitter query expressions against the selected grammar and returns capture ranges.
- **Optional Regex:** Oniguruma-based matching can complement AST/query rules for patterns that are not naturally represented by syntax nodes.
- **Hybrid profiles:** ATS/query and Regex rules can be enabled together. The sample assets include isolated profiles to test each backend independently.

### Configuration and rendering responsibilities

A `.tsh` file describes language metadata, editor metadata, semantic styles, color schemes and highlighting rules. The TSH engine compiles the configuration and produces capture/style results. The editor integration is responsible for mapping those results into actual editor spans or render instructions.

For custom integrations, preserve the coordinate system and document revision carried by the API. In particular, do not install a RenderPlan produced for an earlier document revision.

### Large-document design

The codebase includes bounded UTF-16LE input windows in the parser binding, Piece Tree-backed editor buffers, range-oriented highlighting APIs and capture/render caching. Windowed input reduces transient full-document copies; it does not mean that Tree-sitter builds a partial AST. Actual parse cost, query/capture cost and editor rendering cost are separate measurements and should be benchmarked separately.

## Available grammar languages

The following grammar directories are included in the repository's grammar catalogue. The directory name is shown in parentheses where it differs from the display name. The catalogue indicates source integration, **not** that every grammar is published as a Maven artifact.

| Language | Grammar module |
| --- | --- |
| AIDL | [aidl](grammars/aidl) |
| ARM64 | [arm64](grammars/arm64) |
| AsciiDoc | [asciidoc](grammars/asciidoc) |
| Assembly | [asm](grammars/asm) |
| Bash | [bash](grammars/bash) |
| C | [c](grammars/c) |
| CMake | [cmake](grammars/cmake) |
| Containerfile | [containerfile](grammars/containerfile) |
| C++ | [cpp](grammars/cpp) |
| C# | [csharp](grammars/csharp) |
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
| LLVM IR | [llvm](grammars/llvm) |
| LLVM MIR | [llvm_mir](grammars/llvm_mir) |
| Logcat | [logcat](grammars/logcat) |
| Lua | [lua](grammars/lua) |
| Make | [make](grammars/make) |
| Markdown | [markdwon](grammars/markdwon) |
| PHP | [php](grammars/php) |
| Properties | [properties](grammars/properties) |
| Protocol Buffers | [proto](grammars/proto) |
| Python | [python](grammars/python) |
| Regex | [regex](grammars/regex) |
| Requirements | [requirements](grammars/requirements) |
| reStructuredText | [rst](grammars/rst) |
| Ruby | [ruby](grammars/ruby) |
| Rust | [rust](grammars/rust) |
| SCSS | [scss](grammars/scss) |
| Smali | [smali](grammars/smali) |
| SQL | [sql](grammars/sql) |
| SSH configuration | [ssh](grammars/ssh) |
| Starlark | [starlark](grammars/starlark) |
| Svelte | [svelte](grammars/svelte) |
| Swift | [swift](grammars/swift) |
| TOML | [toml](grammars/toml) |
| TypeScript | [typescript](grammars/typescript) |
| Vue | [vue](grammars/vue) |
| XML | [xml](grammars/xml) |
| YAML | [yaml](grammars/yaml) |
| Zsh | [zsh](grammars/zsh) |

## Build environment

The versions below are taken from the repository's Gradle version catalog, Gradle wrapper and the active GitHub Actions build workflow. Keep local tool versions aligned with these pins when reproducing CI.

| Tool | Project / CI version |
| --- | --- |
| JDK | 17 (Temurin in CI) |
| Gradle | Wrapper 8.14.8 |
| Android Gradle Plugin | 8.13.2 |
| Kotlin Gradle plugin | 2.4.21 |
| Android NDK | r29 / 29.0.14206865 |
| Android CMake | 3.31.6 |
| Android Build Tools | 35.0.0 |
| Python | 3.12 |
| Node.js | 22.22.2 |
| npm | Bundled with Node.js |
| Bun | Installed by the workflow |
| Rust / Cargo | Required by native/grammar build dependencies |
| Ninja | Required native build tool |
| Git | Required, including recursive submodule support |

The Android build configuration currently sets compile SDK 34 and min SDK 21. Android Studio must have a compatible Android SDK platform installed; install the NDK and CMake versions listed above through SDK Manager or the command-line SDK manager.

### Android Studio setup

1. Install a JDK 17 distribution (Temurin is used in CI) and configure Android Studio's **Gradle JDK** to that JDK.
2. Open the repository root, not an individual module.
3. In **Tools → SDK Manager → SDK Platforms**, install Android API 34.
4. In **SDK Tools**, enable **Show Package Details**, then install:
   - Android SDK Build-Tools 35.0.0
   - NDK (Side by side) 29.0.14206865
   - CMake 3.31.6
   - Android SDK Command-line Tools (latest)
5. Accept the Android SDK licenses.
6. Ensure the project uses the checked-in Gradle wrapper (8.14.8); do not replace it with a system Gradle installation.
7. Initialize Git submodules before Gradle sync/build. The grammar sources and Tree-sitter runtime are submodules.

Recommended environment variables:

- `JAVA_HOME`: JDK 17 installation directory.
- `ANDROID_SDK_ROOT` (or `ANDROID_HOME`): Android SDK directory.
- `ANDROID_NDK_HOME`: `<ANDROID_SDK_ROOT>/ndk/29.0.14206865`.

## Build on Linux

The CI workflow runs on Ubuntu and installs the following system packages:

```bash
sudo apt-get update
sudo apt-get install -y --no-install-recommends \
  build-essential cargo nodejs cmake ninja-build
```

Install the pinned JDK, Android SDK, NDK and CMake versions listed above. Install Python 3.12, Node.js 22.22.2 and Bun. Then clone and initialize the repository:

```bash
git clone --recurse-submodules https://github.com/msmt2018/Android-Windbell-TreeSitter.git
cd Android-Windbell-TreeSitter
git submodule sync --recursive
git submodule update --init --recursive
```

Install dependencies for grammar packages that declare a `package.json`. The CI workflow discovers package manifests, installs each npm workspace root only once, skips workspace-managed child packages and limits parallel installation to four packages. For an individual grammar, for example:

```bash
cd grammars/cpp
npm install --legacy-peer-deps --no-audit --no-fund
cd ../..
```

The Java grammar is pinned to ABI 15 by the repository helper. Run it before a manual native build if the parser source does not already contain ABI 15:

```bash
bash scripts/generate-java-grammar-abi15.sh
```

Build the release APK and debug symbols:

```bash
./gradlew assembleRelease generateDebugSymbolsRelease --parallel --stacktrace
```

The APK is written under `app/build/outputs/apk/release/`.

Run JVM/unit tests and compile Android instrumentation-test sources:

```bash
./gradlew testDebug \
  :editor:compileDebugAndroidTestKotlin \
  :app:compileDebugAndroidTestKotlin \
  --continue --parallel --stacktrace
```

The CI workflow additionally builds the host JNI library, runs native TSH cache tests and large-file storage tests under ASan/UBSan, and runs Android instrumentation tests on emulator API 26, 30 and 35.

## Build on Windows

The repository's checked-in CI workflow is Linux-based; Windows is not a currently demonstrated CI target. The most reproducible Windows setup is **WSL2 + Ubuntu**, following the Linux instructions above.

For a native Windows installation:

1. Install Android Studio, JDK 17, Android SDK Platform 34, Build-Tools 35.0.0, NDK 29.0.14206865 and CMake 3.31.6.
2. Install Git for Windows (including Git Bash), Python 3.12, Node.js 22.22.2, Bun, Rust/Cargo and Ninja.
3. Use a Bash-capable terminal for repository scripts such as `scripts/generate-java-grammar-abi15.sh`. Native Windows builds may also need MSYS2 build utilities (`bash`, `sed`, `find`, `make`/compiler tools) depending on the grammar package.
4. Set `JAVA_HOME`, `ANDROID_SDK_ROOT` and `ANDROID_NDK_HOME`; ensure the JDK, SDK command-line tools, NDK, CMake, Ninja, Python, Node and Rust binaries are on `PATH`.
5. Clone the repository with recursive submodules and install grammar npm dependencies as described above.
6. Run Gradle from PowerShell with the wrapper:

```powershell
.\gradlew.bat assembleRelease generateDebugSymbolsRelease --parallel --stacktrace
.\gradlew.bat testDebug --continue --parallel --stacktrace
```

If a grammar helper requires Bash, invoke it through Git Bash or WSL. Native Windows compatibility depends on each grammar's own build scripts and native dependencies; use WSL2 when a build fails due to shell/toolchain differences.

## Grammar manifest generator: `generate_grammars.py`

Run this script from the repository root after initializing the grammar submodules:

```bash
python generate_grammars.py
```

It generates `grammars/grammars.json`. The script is designed to keep the manifest synchronized with the actual grammar submodules rather than requiring manual maintenance.

### What it does

1. Reads `.gitmodules` and extracts registered paths under `grammars/`.
2. Intersects those paths with grammar directories that actually exist locally. An uninitialized submodule is therefore not treated as a local grammar source.
3. Recursively searches each grammar directory for `src/parser.c` or `src/parser.cc`.
4. Excludes hidden paths and the directory names `node_modules`, `.git`, `.github`, `build` and `target`.
5. Extracts the actual `tree_sitter_*` C entry-point symbol from parser source when possible.
6. Detects composite grammars and records their `sub.paths` and `sub.entries`; single-parser grammars record `entry`.
7. Detects an additional scanner source (`scanner.c` or `scanner.cc`) and records it in `src.extra`.
8. Writes to a temporary JSON file and renames it to `grammars/grammars.json` after generation.

The root Gradle settings apply the dynamic grammar module plugin, which consumes the generated grammar metadata when configuring the project.

**This script does not** clone submodules, install npm packages, generate parser source files, compile native libraries or publish Maven artifacts. Those are separate steps in the build workflow.

After running it, inspect the generated changes:

```bash
git diff -- grammars/grammars.json
```

## Adding or updating a grammar

1. Add the upstream grammar as a Git submodule under `grammars/<module-name>`.
2. Initialize/update the submodule recursively.
3. Ensure its parser source exists (usually `src/parser.c` or `src/parser.cc`) and review any scanner or npm build requirements.
4. Run `python generate_grammars.py` to refresh `grammars/grammars.json`.
5. Sync Gradle and build the target module.
6. Verify the generated JNI language binding and packaged `.so` for the ABIs your application supports.
7. Review the upstream grammar's license and preserve its notices.

Example:

```bash
git submodule add <upstream-grammar-url> grammars/<module-name>
git submodule update --init --recursive
python generate_grammars.py
```

## Native grammar loading

Prefer packaging the grammar library as an application dependency. Runtime loading of external native grammar libraries requires a compatible ABI and library path, and the loaded language must follow the API's native handle lifecycle. See `TSLanguage.loadLanguage(...)` and `TSLanguage.close()` in the current binding sources before adopting dynamic loading.

## License and third-party notices

The root `LICENSE` file contains the GNU Affero General Public License, version 3 (AGPL-3.0-only). The AGPL requires preservation of notices and corresponding source obligations when conveying covered works; section 13 adds source-offer requirements for modified versions used for remote network interaction.

The grammar directories are upstream projects maintained by their respective authors. A Git submodule does not automatically change its upstream license. Review each grammar's license and notices before redistribution. The same applies to vendored or nested third-party components, including Tree-sitter and Oniguruma.

- [Full license text](LICENSE)
- [GNU AGPL v3 — official text](https://www.gnu.org/licenses/agpl-3.0.html)
- [JitPack multi-module documentation](https://docs.jitpack.io/building/)
- [JitPack Android documentation](https://docs.jitpack.io/android/)
- [Tree-sitter documentation](https://tree-sitter.github.io/tree-sitter/)

---

<p align="center">
  Android-native parsing · Tree-sitter AST · Windbell TSH · Modular grammars
</p>
