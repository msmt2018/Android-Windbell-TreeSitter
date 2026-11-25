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
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
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
