plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.zakocoder.app"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "dev.zakocoder.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-dev"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    ndkVersion = "28.2.13676358"
    defaultConfig { ndk { abiFilters += "arm64-v8a" } }
    sourceSets.getByName("main").jniLibs.srcDir(rootProject.file("runtime-artifacts/jniLibs"))
    packaging { jniLibs { useLegacyPackaging = true; keepDebugSymbols += "**/libzakocoder_*.so" } }
    buildFeatures { compose = true }
    testOptions { unitTests.isReturnDefaultValues = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    lint { warningsAsErrors = true }
}

val prepareRuntime by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    commandLine("python3", "scripts/prepare_runtime.py")
    inputs.file(rootProject.file("runtime/artifacts.json"))
    inputs.file(rootProject.file("scripts/prepare_runtime.py"))
    // Always verify, including files already present. No unverified local override.
}
tasks.named("preBuild").configure { dependsOn(prepareRuntime) }
tasks.matching { it.name.contains("Release") }.configureEach {
    doFirst { error("Validation-only PRoot binaries: complete self-build provenance before release packaging") }
}

dependencies {
    implementation(project(":terminal-view"))
    implementation(libs.okhttp)
    implementation(libs.commonmark)
    implementation(libs.commonmark.tables)
    androidTestImplementation(libs.okhttp.mock)
    androidTestImplementation(libs.okhttp.tls)
    implementation(libs.sora.editor)
    implementation(libs.sora.textmate)
    implementation(libs.commons.compress)
    implementation(libs.xz)
    implementation(libs.lifecycle.compose)
    testImplementation(libs.junit)
    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.test.core)
    androidTestImplementation(libs.junit)
    implementation(libs.androidx.core)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.coroutines.android)
    debugImplementation(libs.compose.tooling)
}
