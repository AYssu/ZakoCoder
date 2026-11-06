plugins {
    alias(libs.plugins.android.application)
}

dependencies { testImplementation(libs.junit) }

// Optional Lite companion APK. Full embeds the same service sources and kernel in the main APK.
val compatibilityAbis = providers.gradleProperty("compatibilityAbis").orElse("arm64-v8a").get()
    .split(",").map(String::trim).distinct().also { abis ->
        require(abis.isNotEmpty() && abis.all { it in setOf("arm64-v8a", "x86_64") }) {
            "Unsupported compatibility ABI"
        }
    }
val pluginVersionCode = providers.gradleProperty("kernelPluginVersionCode").orElse("1").get().toInt()
val pluginVersionName = providers.gradleProperty("kernelPluginVersionName").orElse("1.0.0").get()
val runtimeVerification = providers.gradleProperty("runtimeVerification").orNull == "true"
val mainApplicationId = if (runtimeVerification) "dev.zakocoder.app.runtimecheck" else "dev.zakocoder.app"
val pluginPackage = "$mainApplicationId.kernel"
val kernelPermission = "$mainApplicationId.permission.KERNEL"

android {
    namespace = "dev.zakocoder.app.kernel"
    compileSdk = 37
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = pluginPackage
        minSdk = 26
        targetSdk = 36
        versionCode = pluginVersionCode
        versionName = pluginVersionName
        ndk { abiFilters += compatibilityAbis }
        manifestPlaceholders["minMainVersion"] = "1"
        manifestPlaceholders["kernelPermission"] = kernelPermission
        buildConfigField("String", "KERNEL_PERMISSION", "\"$kernelPermission\"")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        aidl = true
        buildConfig = true
    }
    sourceSets.getByName("main").java.srcDir(rootProject.file("kernel-shared/src/main/java"))
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
