/*
 * Copyright © 2026 Github Lzhiyong
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.gradle.api.tasks.testing.logging.TestExceptionFormat.*
import org.gradle.api.tasks.testing.logging.TestLogEvent.*
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.zerostudio.idea.piecetable"
    compileSdk = 34
    
    defaultConfig {
        minSdk = 21
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    
    buildFeatures {
        viewBinding = true
    }
    
    sourceSets {
        getByName("test") {
            java.srcDir("src/sharedTest/java")
            kotlin.srcDir("src/sharedTest/java")
        }
        getByName("androidTest") {
            java.srcDir("src/sharedTest/java")
            kotlin.srcDir("src/sharedTest/java")
        }
    }
    
    testOptions {
        unitTests.all {
            it.forkEvery = 0
        }
    }
}

tasks.withType<KotlinCompile>().configureEach {
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=kotlin.ExperimentalUnsignedTypes"
        )
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()    
    testLogging {
        events(PASSED, FAILED, STANDARD_OUT, STANDARD_ERROR, SKIPPED)        
        showExceptions = true
        showCauses = true        
        showStandardStreams = true
    }
    
    afterSuite(KotlinClosure2<TestDescriptor, TestResult, Unit>({ suite, result ->
        if (suite.parent == null) {
            println(
                "\nresult ${result.resultType}  " +
                "total: ${result.testCount}，Passed: ${result.successfulTestCount}，" +
                "Failure: ${result.failedTestCount}，Skipped: ${result.skippedTestCount}"
            )
        }
    }))
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
    implementation(libs.kotlinx.serialization.json)
    
    testImplementation(libs.kotlin.test.junit5)
    androidTestImplementation(libs.kotlin.test.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.rules)
    androidTestImplementation(libs.androidx.runner)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}