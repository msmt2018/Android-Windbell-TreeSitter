@file:Suppress("UnstableApiUsage")

import com.android.build.gradle.BaseExtension
import com.android.build.gradle.LibraryExtension
import com.zerostudio.idea.treesitter.BuildTreeSitterTask
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import com.zerostudio.idea.treesitter.CleanTreeSitterBuildTask
import com.zerostudio.idea.treesitter.projectVersionCode
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

buildscript {
  dependencies {
    classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
  }
}

@Suppress("DSL_SCOPE_VIOLATION") plugins {
  id("build-logic.root-project")
  alias(libs.plugins.kotlin) apply false
  alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.parcelize) apply false
}


// Android versionCode keeps its timestamp-based uniqueness.
val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))

// Maven version format: yyyyMMdd-<7-character Git HEAD hash>.
// Fail the build if Git metadata is unavailable; never publish an ambiguous "unknown" version.
val buildDate = LocalDate.now(ZoneOffset.UTC)
  .format(DateTimeFormatter.BASIC_ISO_DATE)

val gitCommit = provider {
  val stdout = ByteArrayOutputStream()
  exec {
    commandLine("git", "rev-parse", "--short=7", "HEAD")
    standardOutput = stdout
  }
  stdout.toString().trim().also {
    if (!it.matches(Regex("[0-9a-fA-F]{7}"))) {
      throw GradleException("Unable to determine the current Git commit hash for the Maven version: '$it'")
    }
  }
}.get()

val buildVersionName = "$buildDate-$gitCommit"

// Android versionCode is derived from the full build timestamp and kept within Int range.
val buildVersionCode = timestamp.substring(2).toLong().let {
    if (it > Int.MAX_VALUE) (it % Int.MAX_VALUE).toInt() else it.toInt()
}

// Android uses the leading 'v'; Maven publications remove it.
// val releaseVersion = providers.gradleProperty("releaseVersion").orElse("1.0.0")
// version = "v${releaseVersion.get().removePrefix("v")}"


val pinnedNdkVersion = libs.versions.ndk.get()
val pinnedCmakeVersion = libs.versions.cmake.get()
val pinnedBuildToolsVersion = libs.versions.build.tools.get()

fun Project.configureBaseExtension() {
  extensions.configure<BaseExtension> {
    compileSdkVersion(34)
    buildToolsVersion(pinnedBuildToolsVersion)

    defaultConfig {
      minSdk = 21
      targetSdk = 34
      versionName = buildVersionName
      versionCode = buildVersionCode
    }

    compileOptions {
      sourceCompatibility = BuildConfig.JAVA_VERSION
      targetCompatibility = BuildConfig.JAVA_VERSION

      isCoreLibraryDesugaringEnabled = true
    }

    buildTypes {
      getByName("release") {
        isMinifyEnabled = false
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"),
          "proguard-rules.pro")
      }
    }

    configurations.getByName("coreLibraryDesugaring").dependencies.add(
      libs.common.coreLibDesugaring.get())
  }
}

subprojects {
  plugins.withId("com.android.application") { configureBaseExtension() }
  plugins.withId("com.android.library") { configureBaseExtension() }
  plugins.withId("java-library") {
    tasks.withType(JavaCompile::class.java) {
      sourceCompatibility = BuildConfig.JAVA_VERSION.majorVersion
      targetCompatibility = BuildConfig.JAVA_VERSION.majorVersion
    }
  }
  plugins.withId("android-tree-sitter.ts") {
    configureTsModule()

    // set java library path for tests
    tasks.withType<Test> {
      systemProperty("java.library.path",
        rootProject.buildDir.resolve("host").absolutePath)
    }
  }

  val jitPackModules = setOf(
    "android-tree-sitter",
    "annotations",
    "tree-sitter-aidl",
    "tree-sitter-arm64",
    "tree-sitter-asciidoc",
    "tree-sitter-asm",
    "tree-sitter-bash",
    "tree-sitter-c",
    "tree-sitter-cmake",
    "tree-sitter-containerfile",
    "tree-sitter-cpp",
    "tree-sitter-csharp",
    "tree-sitter-csv",
    "tree-sitter-dart",
    "tree-sitter-diff",
    "tree-sitter-disassembly",
    "tree-sitter-dockerfile",
    "tree-sitter-gdb",
    "tree-sitter-git_rebase",
    "tree-sitter-gitattributes",
    "tree-sitter-gitcommit",
    "tree-sitter-gitignore",
    "tree-sitter-go",
    "tree-sitter-gomod",
    "tree-sitter-graphql",
    "tree-sitter-groovy",
    "tree-sitter-ini",
    "tree-sitter-java",
    "tree-sitter-javascript",
    "tree-sitter-jsdoc",
    "tree-sitter-json",
    "tree-sitter-kotlin",
    "tree-sitter-llvm",
    "tree-sitter-llvm_mir",
    "tree-sitter-logcat",
    "tree-sitter-lua",
    "tree-sitter-make",
    "tree-sitter-markdwon",
    "tree-sitter-php",
    "tree-sitter-properties",
    "tree-sitter-proto",
    "tree-sitter-python",
    "tree-sitter-regex",
    "tree-sitter-requirements",
    "tree-sitter-rst",
    "tree-sitter-ruby",
    "tree-sitter-rust",
    "tree-sitter-scss",
    "tree-sitter-smali",
    "tree-sitter-sql",
    "tree-sitter-ssh",
    "tree-sitter-starlark",
    "tree-sitter-svelte",
    "tree-sitter-swift",
    "tree-sitter-toml",
    "tree-sitter-typescript",
    "tree-sitter-vue",
    "tree-sitter-xml",
    "tree-sitter-yaml",
    "tree-sitter-zsh",
  )

  if (name in jitPackModules) {
    group = providers.environmentVariable("GROUP")
      .orElse("io.github.msmt2018")
      .get()
    version = providers.environmentVariable("VERSION")
      .orElse(providers.gradleProperty("jitpackVersion"))
      .orElse(providers.environmentVariable("JITPACK_TAG"))
      .orElse(buildVersionName)
      .get()

    plugins.withId("com.android.library") {
      extensions.configure<LibraryExtension> {
        publishing {
          singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
          }
        }
      }
    }

    plugins.withId("java-library") {
      extensions.configure<JavaPluginExtension> {
        withSourcesJar()
        withJavadocJar()
      }
    }

    plugins.withId("maven-publish") {
      afterEvaluate {
        extensions.configure<PublishingExtension> {
          publications {
            create<MavenPublication>("release") {
              artifactId = project.name
              from(components[if (plugins.hasPlugin("com.android.library")) "release" else "java"])

              pom {
                name.set(project.name)
                description.set(project.description
                  ?: "${project.name} for Android Tree-sitter")
                url.set("https://github.com/msmt2018/Android-Windbell-TreeSitter")
                licenses {
                  license {
                    name.set("GNU Affero General Public License v3.0")
                    url.set("https://www.gnu.org/licenses/agpl-3.0.html")
                    distribution.set("repo")
                  }
                }
                developers {
                  developer {
                    id.set("msmt2018")
                    name.set("msmt2018")
                    url.set("https://github.com/msmt2018")
                  }
                }
                scm {
                  url.set("https://github.com/msmt2018/Android-Windbell-TreeSitter")
                  connection.set("scm:git:https://github.com/msmt2018/Android-Windbell-TreeSitter.git")
                }
              }
            }
          }
        }
      }
    }
  }
}

tasks.register<BuildTreeSitterTask>("buildTreeSitter")

tasks.register<CleanTreeSitterBuildTask>("cleanTreeSitterBuild")

tasks.register<Exec>("generateJavaGrammarAbi15") {
  group = "build setup"
  description = "Regenerates the Java Tree-sitter parser with ABI 15 supertype metadata."
  workingDir(rootProject.projectDir)
  commandLine("bash", "scripts/generate-java-grammar-abi15.sh")
  inputs.file(rootProject.file("grammars/java/src/grammar.json"))
  inputs.file(rootProject.file("scripts/generate-java-grammar-abi15.sh"))
  outputs.file(rootProject.file("grammars/java/src/parser.c"))
  outputs.upToDateWhen {
    val parser = rootProject.file("grammars/java/src/parser.c")
    parser.isFile && parser.useLines { lines ->
      lines.any { it.trim() == "#define LANGUAGE_VERSION 15" }
    }
  }
}

// Keep IDE and CI builds on the same grammar ABI. Generate the Java parser
// before any grammar module configures or builds its native CMake target.
subprojects {
  plugins.withId("android-tree-sitter.ts") {
    tasks.configureEach {
      if (name.startsWith("configureCMake") || name.startsWith("buildCMake")) {
        dependsOn(rootProject.tasks.named("generateJavaGrammarAbi15"))
      }
    }
  }
}

tasks.register<Delete>("clean").configure {
  dependsOn("cleanTreeSitterBuild")
  delete(rootProject.layout.buildDirectory)
  delete(rootProject.file("tree-sitter-lib/cli/build"))
}

fun Project.configureTsModule() {
  extensions.configure<BaseExtension> {
  
    packagingOptions.jniLibs.keepDebugSymbols += "**/*.so"
    
    val grammarName = project.project.name.substringAfter("tree-sitter-", "")
    if (grammarName.isNotBlank()) {
      namespace = "com.zerostudio.idea.treesitter.$grammarName"
      logger.lifecycle("Set namespace '$namespace' to $project")
    }

    ndkVersion = pinnedNdkVersion

    defaultConfig {
      val rootProjDir = project.rootProject.projectDir.absolutePath
      val tsDir = "${rootProjDir}/tree-sitter-lib"

      externalNativeBuild {
        cmake {
          arguments("-DPROJECT_DIR=${rootProjDir}", "-DTS_DIR=${tsDir}")
        }
      }
    }

    externalNativeBuild {
      cmake {
        path = project.file("src/main/cpp/CMakeLists.txt")
        version = pinnedCmakeVersion
      }
    }
  }

  // avoid circular dependency
  if (project.projects.androidTreeSitter.name != project.name) {
    configurations.getByName("api").dependencies.add(
      project.projects.androidTreeSitter)
  }
}

// Disable Android Lint everywhere except the Tree-sitter engine and grammar libraries.
// This affects lint tasks only; compilation, tests and Maven publication remain enabled.
subprojects {
  tasks.configureEach {
    if (name.startsWith("lint", ignoreCase = true)) {
      val isLintEnabledModule =
        project.name == "android-tree-sitter" || project.name.startsWith("tree-sitter-")
      if (!isLintEnabledModule) {
        enabled = false
      }
    }
  }
}
