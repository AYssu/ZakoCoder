plugins {
    alias(libs.plugins.android.application)
}

// Standalone, optional kernel plugin APK. It is the only module that packages Xray; the main app
// builds without Go and without the kernel.
val compatibilityAbis = providers.gradleProperty("compatibilityAbis").orElse("arm64-v8a").get()
    .split(",").map(String::trim).distinct().also { abis ->
        require(abis.isNotEmpty() && abis.all { it in setOf("arm64-v8a", "x86_64") }) {
            "Unsupported compatibility ABI"
        }
    }
val pluginVersionCode = providers.gradleProperty("kernelPluginVersionCode").orElse("1").get().toInt()
val pluginVersionName = providers.gradleProperty("kernelPluginVersionName").orElse("1.0.0").get()

android {
    namespace = "dev.zakocoder.app.kernel"
    compileSdk = 37
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "dev.zakocoder.app.kernel"
        minSdk = 26
        targetSdk = 36
        versionCode = pluginVersionCode
        versionName = pluginVersionName
        ndk { abiFilters += compatibilityAbis }
        manifestPlaceholders["minMainVersion"] = "1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { aidl = true }
    sourceSets.getByName("main").jniLibs.directories.add(
        rootProject.file("runtime-artifacts/kernel-jniLibs").absolutePath,
    )
    packaging {
        jniLibs { useLegacyPackaging = true; keepDebugSymbols += "**/libzakocoder_*.so" }
    }
}

// The plugin is the only consumer of the self-built Xray kernel; the base app preBuild no longer
// runs this task and therefore does not need Go.
val prepareProxyKernel = tasks.register<Exec>("prepareProxyKernel") {
    workingDir(rootProject.projectDir)
    commandLine("python3", "scripts/prepare_proxy_kernel.py")
    inputs.file(rootProject.file("native-kernel/artifacts.lock.json"))
    inputs.file(rootProject.file("scripts/prepare_proxy_kernel.py"))
}
tasks.named("preBuild").configure { dependsOn(prepareProxyKernel) }
