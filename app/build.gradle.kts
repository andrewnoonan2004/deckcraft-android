import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("jacoco")
}

val projectRootHash: String = MessageDigest.getInstance("SHA-1")
    .digest(rootProject.projectDir.canonicalPath.toByteArray())
    .joinToString("") { b: Byte -> "%02x".format(b) }
    .take(8)
// Local (Android Terminal VM) builds keep I/O-heavy outputs on ext4; CI keeps the default app/build.
if (System.getenv("CI") == null) {
    layout.buildDirectory.set(
        file("${System.getProperty("user.home")}/.cache/projects/${rootProject.name}-$projectRootHash/gradle-build/app")
    )
}

base {
    archivesName.set("DeckCraft")
}

android {
    namespace = "com.andrewnoonan.deckcraft"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.andrewnoonan.deckcraft"
        minSdk = 28
        targetSdk = 36
        versionCode = 1600
        versionName = "0.16.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file("keystore/deckcraft.jks")
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "deckcraft_android_keystore_2026"
            keyAlias = System.getenv("KEY_ALIAS") ?: "deckcraft"
            keyPassword = System.getenv("KEY_PASSWORD") ?: "deckcraft_android_keystore_2026"
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("release")
            isDebuggable = true
            enableUnitTestCoverage = true
        }
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            all {
                it.systemProperty("robolectric.offline", "false")
                it.systemProperty("robolectric.conscryptMode", "OFF")
                it.maxHeapSize = "2048m"
            }
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.02.00")
    implementation(composeBom)
    testImplementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.print:print:1.1.0")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3:1.4.0-alpha08")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.google.android.material:material:1.12.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test:core-ktx:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

tasks.register<JacocoReport>("createDebugCoverageReport") {
    dependsOn("testDebugUnitTest")
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
    val debugTree = fileTree("${layout.buildDirectory.get()}/tmp/kotlin-classes/debug")
    sourceDirectories.setFrom(files("$projectDir/src/main/kotlin"))
    classDirectories.setFrom(debugTree)
    executionData.setFrom(fileTree(layout.buildDirectory.get()) {
        include("outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec")
    })
}
