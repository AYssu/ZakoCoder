plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val compatibilityAbis = providers.gradleProperty("compatibilityAbis").orElse("arm64-v8a").get()
    .split(",").map(String::trim).distinct().also { abis ->
        require(abis.isNotEmpty() && abis.all { it in setOf("arm64-v8a", "x86_64") }) { "Unsupported compatibility ABI" }
    }
val compatibilityVersionCode = providers.gradleProperty("compatibilityVersionCode").orNull?.let {
    it.toInt().also { code -> require(code > 0) }
} ?: 1

// Stable distribution remains gated; Beta must opt in with an explicit immutable version.
val betaRelease = providers.gradleProperty("betaRelease").orNull == "true"
val betaVersion = providers.gradleProperty("betaVersion").orNull
val betaVersionCode = providers.gradleProperty("betaVersionCode").orNull?.toIntOrNull()
if (betaRelease) {
    require(betaVersion?.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+-beta\\.[1-9][0-9]*")) == true) {
        "Beta requires -PbetaVersion=<major>.<minor>.<patch>-beta.<positive number>"
    }
    require(betaVersionCode != null && betaVersionCode > 0) { "Beta requires a positive -PbetaVersionCode" }
    require(!providers.gradleProperty("compatibilityVersionCode").isPresent) {
        "Use betaVersionCode alone for Beta builds"
    }
}

android {
    namespace = "dev.zakocoder.app"
    compileSdk = 37
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = if (providers.gradleProperty("runtimeVerification").orNull == "true")
            "dev.zakocoder.app.runtimecheck" else "dev.zakocoder.app"
        minSdk = 26
        targetSdk = 36
        versionCode = if (betaRelease) requireNotNull(betaVersionCode) else compatibilityVersionCode
        versionName = if (betaRelease) requireNotNull(betaVersion) else "0.1.0-dev"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    ndkVersion = "28.2.13676358"
    defaultConfig { ndk { abiFilters += compatibilityAbis } }
    sourceSets.getByName("main").jniLibs.directories.add(rootProject.file("runtime-artifacts/jniLibs").absolutePath)
    packaging { jniLibs { useLegacyPackaging = true; keepDebugSymbols += "**/libzakocoder_*.so" } }
    buildFeatures { compose = true }
    testOptions { unitTests.isReturnDefaultValues = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    lint { warningsAsErrors = true }
}

val prepareRuntime = tasks.register("prepareRuntime")
compatibilityAbis.forEach { abi ->
    val prepareAbi = tasks.register<Exec>("prepareRuntime" + abi.replace("-", "_").replaceFirstChar { it.uppercase() }) {
        workingDir(rootProject.projectDir)
        commandLine("python3", "scripts/prepare_runtime.py", "--ndk", androidComponents.sdkComponents.ndkDirectory.get().asFile.absolutePath, "--abi", abi)
        inputs.file(rootProject.file("runtime/artifacts.json"))
        inputs.file(rootProject.file("scripts/prepare_runtime.py"))
        // Verify cached artifacts on every invocation.
    }
    prepareRuntime.configure { dependsOn(prepareAbi) }
}
tasks.named("preBuild").configure { dependsOn(prepareRuntime) }
tasks.matching { it.name.contains("Release") }.configureEach {
    doFirst {
        check(betaRelease) {
            "Stable Release remains gated; Beta requires explicit version properties and the distribution checks in docs/RELEASING.md"
        }
    }
}

dependencies {
    implementation(project(":terminal-view"))
    implementation(libs.okhttp)
    implementation(libs.jsch)
    implementation(libs.commonmark)
    implementation(libs.commonmark.tables)
    androidTestImplementation(libs.okhttp.mock)
    androidTestImplementation(libs.okhttp.tls)
    implementation(libs.sora.editor)
    implementation(libs.sora.textmate)
    implementation(libs.sora.lsp)
    implementation(libs.lsp4j)
    implementation(libs.commons.compress)
    implementation(libs.xz)
    implementation(libs.lifecycle.compose)
    testImplementation(libs.junit)
    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.test.core)
    androidTestImplementation(libs.junit)
    implementation(libs.androidx.core)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.coroutines.android)
    debugImplementation(libs.compose.tooling)
}
