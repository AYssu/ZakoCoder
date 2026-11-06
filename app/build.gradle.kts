import java.security.MessageDigest

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
val runtimeVerification = providers.gradleProperty("runtimeVerification").orNull == "true"
val appApplicationId = if (runtimeVerification) "dev.zakocoder.app.runtimecheck" else "dev.zakocoder.app"
val kernelPluginPackage = "$appApplicationId.kernel"
val kernelPermission = "$appApplicationId.permission.KERNEL"

// Distribution builds opt in with an explicit immutable version; legacy property names remain compatible.
val betaRelease = providers.gradleProperty("betaRelease").orNull == "true"
val betaVersion = providers.gradleProperty("betaVersion").orNull
val betaVersionCode = providers.gradleProperty("betaVersionCode").orNull?.toIntOrNull()
if (betaRelease) {
    require(betaVersion?.matches(Regex("(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)(?:-(?:beta|rc)\\.[1-9][0-9]*)?")) == true) {
        "Release requires -PbetaVersion=<major>.<minor>.<patch> with optional -beta.N or -rc.N"
    }
    require(betaVersionCode != null && betaVersionCode > 0) { "Release requires a positive -PbetaVersionCode" }
    require(!providers.gradleProperty("compatibilityVersionCode").isPresent) {
        "Use betaVersionCode alone for distribution builds"
    }
}

android {
    namespace = "dev.zakocoder.app"
    compileSdk = 37
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = appApplicationId
        minSdk = 26
        targetSdk = 36
        versionCode = if (betaRelease) requireNotNull(betaVersionCode) else compatibilityVersionCode
        versionName = if (betaRelease) requireNotNull(betaVersion) else "0.1.0-dev"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["kernelPluginPackage"] = kernelPluginPackage
        manifestPlaceholders["kernelPermission"] = kernelPermission
        buildConfigField("String", "KERNEL_PLUGIN_PACKAGE", "\"$kernelPluginPackage\"")
        buildConfigField("String", "KERNEL_PERMISSION", "\"$kernelPermission\"")
    }
    ndkVersion = "28.2.13676358"
    defaultConfig { ndk { abiFilters += compatibilityAbis } }
    sourceSets.getByName("main").jniLibs.directories.add(rootProject.file("runtime-artifacts/jniLibs").absolutePath)
    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += "**/libzakocoder_*.so"
            // The optional kernel lives in the plugin APK; remove stale cache copies defensively.
            excludes += "**/libzakocoder_xray.so"
        }
    }
    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }
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
    val prepareAbi = tasks.register("prepareRuntime" + abi.replace("-", "_").replaceFirstChar { it.uppercase() }) {
        // Always verify; locally this path needs only the JVM supplied by Android Studio.
        doLast {
            val manifest = groovy.json.JsonSlurper().parse(rootProject.file("runtime/artifacts.json")) as Map<*, *>
            val target = if (abi == "arm64-v8a") manifest else
                (manifest["architectures"] as Map<*, *>)[abi] as Map<*, *>
            val expected = (target["nativeArtifacts"] as List<*>).associate { item ->
                val artifact = item as Map<*, *>
                artifact["name"] as String to artifact["sha256"] as String
            }
            val lock = groovy.json.JsonSlurper().parse(rootProject.file("native-runtime/artifacts.lock.json")) as Map<*, *>
            val locked = if (abi == "arm64-v8a") lock else
                (lock["architectures"] as Map<*, *>)[abi] as Map<*, *>
            check(expected == locked["artifacts"]) { "Runtime manifest differs from the native artifact lock" }
            val source = rootProject.file("native-runtime/prebuilt/$abi")
            val rebuild = providers.gradleProperty("rebuildRuntime").orNull == "true"
            if (!rebuild && expected.keys.all { source.resolve(it).isFile }) {
                // A pinned digest authenticates the same ELF contract checked in source-build CI.
                expected.forEach { (name, digest) ->
                    val actual = MessageDigest.getInstance("SHA-256")
                        .digest(source.resolve(name).readBytes()).joinToString("") { "%02x".format(it) }
                    check(actual == digest) { "Pinned runtime checksum mismatch: $abi/$name" }
                }
                val output = rootProject.file("runtime-artifacts/jniLibs/$abi").apply { mkdirs() }
                expected.keys.forEach { source.resolve(it).copyTo(output.resolve(it), overwrite = true) }
                logger.lifecycle("Repository PRoot and loader verified and prepared ($abi); no native compiler or Python needed")
            } else {
                val command = mutableListOf("python3", "scripts/prepare_runtime.py", "--ndk",
                    androidComponents.sdkComponents.ndkDirectory.get().asFile.absolutePath, "--abi", abi)
                if (rebuild) command += "--build-from-source"
                providers.exec { workingDir(rootProject.projectDir); commandLine(command) }.result.get().assertNormalExitValue()
            }
        }
    }
    prepareRuntime.configure { dependsOn(prepareAbi) }
}
tasks.named("preBuild").configure {
    dependsOn(prepareRuntime)
    doFirst {
        // Old builds wrote the kernel into runtime-artifacts/jniLibs; delete any stale copy before merge.
        rootProject.file("runtime-artifacts/jniLibs").walkTopDown()
            .filter { it.isFile && it.name == "libzakocoder_xray.so" }
            .forEach { it.delete() }
    }
}
tasks.matching { it.name.contains("Release") }.configureEach {
    doFirst {
        check(betaRelease) {
            "Distribution requires explicit version properties and the checks in docs/RELEASING.md"
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
