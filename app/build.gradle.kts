import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

/*
 *  This file is part of android-tree-sitter.
 *
 *  android-tree-sitter library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public
 *  License as published by the Free Software Foundation; either
 *  version 2.1 of the License, or (at your option) any later version.
 *
 *  android-tree-sitter library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  Lesser General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *  along with android-tree-sitter.  If not, see <https://www.gnu.org/licenses/>.
 */
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.zerostudio.idea.androidtreesitter"
    compileSdk = 33

    defaultConfig {
        applicationId = "com.zerostudio.idea.androidtreesitter"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release signing is opt-in and must be supplied by the build environment.
    // Never commit keystores or signing credentials to the repository.
    val signingStorePath = providers.gradleProperty("ANDROID_SIGNING_STORE_FILE").orNull
        ?: System.getenv("ANDROID_SIGNING_STORE_FILE")
    val signingStorePassword = providers.gradleProperty("ANDROID_SIGNING_STORE_PASSWORD").orNull
        ?: System.getenv("ANDROID_SIGNING_STORE_PASSWORD")
    val signingKeyAlias = providers.gradleProperty("ANDROID_SIGNING_KEY_ALIAS").orNull
        ?: System.getenv("ANDROID_SIGNING_KEY_ALIAS")
    val signingKeyPassword = providers.gradleProperty("ANDROID_SIGNING_KEY_PASSWORD").orNull
        ?: System.getenv("ANDROID_SIGNING_KEY_PASSWORD")
    val releaseSigning = if (
        !signingStorePath.isNullOrBlank() &&
        !signingStorePassword.isNullOrBlank() &&
        !signingKeyAlias.isNullOrBlank() &&
        !signingKeyPassword.isNullOrBlank()
    ) {
        signingConfigs.create("releaseCredentials") {
            storeFile = file(signingStorePath)
            storePassword = signingStorePassword
            keyAlias = signingKeyAlias
            keyPassword = signingKeyPassword
        }
    } else null

    buildTypes {
        release {
            signingConfig = releaseSigning
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        viewBinding = true
    }
}

tasks.withType(KotlinCompile::class.java) {
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.common.kotlin.coroutines.android)
    implementation(libs.material)

    implementation(projects.androidTreeSitter)
    implementation(project(":editor"))
    implementation(project(":piecetable"))
    implementation(projects.treeSitterJava)
    implementation(projects.treeSitterJson)
    implementation(projects.treeSitterKotlin)
    implementation(projects.treeSitterLogcat)
    implementation(projects.treeSitterPython)
    implementation(projects.treeSitterXml)
   
    implementation(projects.treeSitterC)
    
    testImplementation(libs.tests.junit)
    androidTestImplementation(libs.tests.androidx.ext.junit)
    androidTestImplementation(libs.tests.androidx.espresso.core)
}
