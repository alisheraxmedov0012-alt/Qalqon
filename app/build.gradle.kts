import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// ---------------------------------------------------------------------------
// Production release signing.
//
// The signing secrets are NEVER stored in this file. They are read, in order of
// precedence, from:
//   1. environment variables (how CI supplies them, via repository secrets), or
//   2. `local.properties` (machine-local, gitignored), for a developer machine.
//
// The keystore itself lives outside the repository. If neither source provides a
// complete set, the release build is not signed here — and `assembleRelease` then
// fails loudly rather than quietly emitting an unsigned APK that could be
// mistaken for a production release.
// ---------------------------------------------------------------------------
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

/** Environment variable first, then `local.properties`; never logged. */
fun signingSecret(environmentName: String, propertyName: String): String? =
    System.getenv(environmentName)?.takeIf { it.isNotBlank() }
        ?: localProperties.getProperty(propertyName)?.takeIf { it.isNotBlank() }

val releaseStoreFile = signingSecret("QALQON_RELEASE_STORE_FILE", "qalqon.storeFile")
val releaseStorePassword = signingSecret("QALQON_RELEASE_STORE_PASSWORD", "qalqon.storePassword")
val releaseKeyAlias = signingSecret("QALQON_RELEASE_KEY_ALIAS", "qalqon.keyAlias")
val releaseKeyPassword = signingSecret("QALQON_RELEASE_KEY_PASSWORD", "qalqon.keyPassword")

val hasReleaseSigning = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() } && file(releaseStoreFile!!).exists()

// Version source of truth. The values below are the defaults; a release pipeline
// may override them for a single build without editing this file, e.g.
//   ./gradlew :app:assembleRelease -PqalqonVersionCode=2 -PqalqonVersionName=0.1.1
val defaultVersionCode = 1
val defaultVersionName = "0.1.0"
val resolvedVersionCode = (project.findProperty("qalqonVersionCode") as String?)?.toIntOrNull()
    ?: defaultVersionCode
val resolvedVersionName = (project.findProperty("qalqonVersionName") as String?)?.takeIf { it.isNotBlank() }
    ?: defaultVersionName

android {
    namespace = "uz.faceguard.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "uz.faceguard.app"
        minSdk = 26
        targetSdk = 35
        versionCode = resolvedVersionCode
        versionName = resolvedVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // Only created when a real identity is available, so a machine without the
        // secrets can still build debug/tests without any signing material.
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                // v1 is unnecessary at minSdk 26; v2 covers installation and v3 adds
                // the rotation lineage a long-lived release identity will want.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // A permanent release identity, deliberately separate from the debug
            // key, so successive release APKs update over one another.
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    buildFeatures {
        compose = true
        // Phase 14: BuildConfig.DEBUG gates the developer-only screens so they are
        // present in debug builds and absent from release.
        buildConfig = true
    }
    androidResources {
        // TFLite models must stay uncompressed so the Interpreter can mmap them.
        noCompress += "tflite"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    // System biometric unlock for the parent UI (PinUnlockScreen).
    implementation(libs.androidx.biometric)
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.mlkit.face.detection)
    // Phase A1: Gemini Nano capability detection only (no generation wired in). On-device;
    // no INTERNET permission and no cloud client are introduced by this dependency.
    implementation(libs.mlkit.genai.prompt)
    implementation(libs.tensorflow.lite)
    implementation(libs.accompanist.permissions)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    testImplementation(libs.junit)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.androidx.room.testing)
    debugImplementation(libs.compose.ui.tooling)
}

// A release APK must never be produced unsigned and then distributed as if it
// were a production build. When no signing identity is available the release
// packaging fails with an actionable message instead. Debug builds, JVM tests
// and lint are unaffected, so a machine without the secrets still works.
tasks.matching { it.name == "assembleRelease" || it.name == "packageRelease" }.configureEach {
    doFirst {
        if (!hasReleaseSigning) {
            throw GradleException(
                "Release signing is not configured, so no release APK can be produced. " +
                    "Provide QALQON_RELEASE_STORE_FILE, QALQON_RELEASE_STORE_PASSWORD, " +
                    "QALQON_RELEASE_KEY_ALIAS and QALQON_RELEASE_KEY_PASSWORD as environment " +
                    "variables, or set the equivalent qalqon.* keys in local.properties.",
            )
        }
    }
}

// TEMPORARY verification bridge.
// The repository CI workflow only runs `assembleDebug`, and adding explicit
// `test` / `connectedAndroidTest` steps requires a token with the `workflow`
// scope. Until that workflow is updated, route the JVM unit tests and the
// instrumented-test APK compilation through the debug assembly so CI verifies
// both without an emulator.
// NOTE: instrumented test *execution* is NOT covered here — see README.
// Remove this block once CI runs the tasks directly.
tasks.matching { it.name == "assembleDebug" }.configureEach {
    dependsOn("testDebugUnitTest")
    dependsOn("assembleDebugAndroidTest")
}
