@file:Suppress("UnstableApiUsage")

import com.android.build.gradle.BaseExtension
import com.android.build.gradle.LibraryExtension
import com.zerostudio.idea.treesitter.BuildTreeSitterTask
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import com.zerostudio.idea.treesitter.CleanTreeSitterBuildTask
import com.zerostudio.idea.treesitter.projectVersionCode
import org.gradle.plugins.signing.SigningExtension
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

buildscript {
  dependencies {
    classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.22")
  }
}

@Suppress("DSL_SCOPE_VIOLATION") plugins {
  id("build-logic.root-project")
  alias(libs.plugins.kotlin) apply false
  alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.parcelize) apply false
}




// 获取当前时间戳 (格式: 20261102212545)
val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))

// 获取 Git 当前 Commit 的 Short Hash (如果获取失败则用 "unknown" 保底)
val gitCommit = provider {
    try {
        val stdout = ByteArrayOutputStream()
        exec {
            commandLine("git", "rev-parse", "--short", "HEAD")
            standardOutput = stdout
            isIgnoreExitValue = true // 避免在无 Git 环境下构建直接崩溃
        }
        stdout.toString().trim().ifEmpty { "unknown" }
    } catch (e: Exception) {
        "unknown"
    }
}.get()

// 拼接生成最终的字符串版本号
val buildVersionName = "$timestamp-$gitCommit"

// 将时间戳转换为纯数字，用于给 Android 提供唯一的、递增的 VersionCode 
// 因为 20261102212545 超过了 Int.MAX_VALUE (2147483647)，这里截取去掉年份前两位或采用自增算法
// 截取后如：2611022125 刚好在 Int 范围内，可以完美作为自增的 VersionCode
val buildVersionCode = timestamp.substring(2).toLong().let { 
    if (it > Int.MAX_VALUE) (it % Int.MAX_VALUE).toInt() else it.toInt() 
}

// Android uses the leading 'v'; Maven publications remove it.
// val releaseVersion = providers.gradleProperty("releaseVersion").orElse("1.0.0")
// version = "v${releaseVersion.get().removePrefix("v")}" 

fun Project.githubPackagesOwner(): String =
  providers.gradleProperty("gpr.owner")
    .orElse(providers.environmentVariable("GITHUB_REPOSITORY_OWNER"))
    .orElse("YOUR_GITHUB_OWNER")
    .get()

fun Project.githubPackagesRepository(): String =
  providers.gradleProperty("gpr.repository")
    .orElse(providers.environmentVariable("GITHUB_REPOSITORY").map { it.substringAfter('/') })
    .orElse("android-tree-sitter")
    .get()

fun Project.configureBaseExtension() {
  extensions.configure<BaseExtension> {
    compileSdkVersion(34)

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

  val githubPackageModules = setOf(
    "android-tree-sitter",
    "annotations",
    "tree-sitter-cpp",
    "tree-sitter-groovy",
    "tree-sitter-java",
    "tree-sitter-json",
    "tree-sitter-kotlin",
    "tree-sitter-log",
    "tree-sitter-xml",
  )

  if (name in githubPackageModules) {
    group = "io.github.msmt2018"
    version = buildVersionName

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
      apply(plugin = "signing")

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
                url.set("https://github.com/${githubPackagesOwner()}/${githubPackagesRepository()}")
                licenses {
                  license {
                    name.set("GNU Lesser General Public License v3.0")
                    url.set("https://www.gnu.org/licenses/gpl-3.0.html#license-text")
                    distribution.set("repo")
                  }
                }
                scm {
                  url.set("https://github.com/${githubPackagesOwner()}/${githubPackagesRepository()}")
                  connection.set("scm:git:https://github.com/${githubPackagesOwner()}/${githubPackagesRepository()}.git")
                }
              }
            }
          }

          repositories {
            maven {
              name = "MavenCentral"
              url = uri("https://sonatype.com")
              credentials {
                username = providers.environmentVariable("MAVEN_USERNAME").orNull
                password = providers.environmentVariable("MAVEN_PASSWORD").orNull
              }
            }
          }
        }

        extensions.configure<SigningExtension> {
          val signingKey = providers.environmentVariable("GPG_SIGNING_KEY").orNull
          val signingPassword = providers.environmentVariable("GPG_PASSWORD").orNull
          if (signingKey != null && signingPassword != null) {
            useInMemoryPgpKeys(signingKey, signingPassword)
            sign(extensions.getByType<PublishingExtension>().publications["release"])
          }
        }
      }
    }
  }
}

tasks.register<BuildTreeSitterTask>("buildTreeSitter")

tasks.register<CleanTreeSitterBuildTask>("cleanTreeSitterBuild")

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

    ndkVersion = "24.0.8215888"

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
        version = "3.22.1"
      }
    }
  }

  // avoid circular dependency
  if (project.projects.androidTreeSitter.name != project.name) {
    configurations.getByName("api").dependencies.add(
      project.projects.androidTreeSitter)
  }
}
