import java.io.File
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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

// Normal Android Studio builds reuse reviewed repository artifacts with JVM-only validation.
// CI separately forces source rebuilds; maintainers can request one with -PrebuildKernel=true.
val prepareProxyKernel = tasks.register("prepareProxyKernel") {
    inputs.file(rootProject.file("native-kernel/artifacts.lock.json"))
    inputs.file(rootProject.file("scripts/prepare_proxy_kernel.py"))
    inputs.property("compatibilityAbis", compatibilityAbis)
    inputs.property("rebuildKernel", providers.gradleProperty("rebuildKernel").orElse("false"))
    // Deliberately verify every invocation: a stale output must never bypass the pinned digest.
    doLast {
        val lock = groovy.json.JsonSlurper().parse(rootProject.file("native-kernel/artifacts.lock.json")) as Map<*, *>
        val targets = lock["targets"] as Map<*, *>
        val rebuild = providers.gradleProperty("rebuildKernel").orNull == "true"
        fun verified(file: File, expected: String): Boolean {
            // The exact SHA also pins length; bound size before reading an untrusted/corrupt file.
            if (!file.isFile || file.length() !in 1L..(128L * 1024 * 1024)) return false
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) } == expected
        }
        compatibilityAbis.forEach { abi ->
            val target = targets[abi] as? Map<*, *> ?: error("Missing pinned kernel target: $abi")
            val asset = target["asset"] as String
            check(asset == "libzakocoder_xray.so") { "Unexpected kernel artifact name" }
            val expected = target["sha256"] as String
            val source = rootProject.file("native-kernel/prebuilt/$abi/$asset")
            val destination = rootProject.file("runtime-artifacts/kernel-jniLibs/$abi/$asset")
            if (!rebuild && verified(source, expected)) {
                destination.parentFile.mkdirs()
                val temporary = Files.createTempFile(destination.parentFile.toPath(), "kernel-", ".part").toFile()
                try {
                    source.copyTo(temporary, overwrite = true)
                    check(verified(temporary, expected)) { "Kernel copy checksum mismatch ($abi)" }
                    Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
                } finally { temporary.delete() }
                logger.lifecycle("Repository Xray kernel verified and prepared ($abi); no Go or Python needed")
            } else {
                logger.lifecycle("Rebuilding pinned Xray kernel ($abi): repository artifact missing, invalid, or rebuild requested")
                try {
                    providers.exec {
                        workingDir(rootProject.projectDir)
                        commandLine("python3", "scripts/prepare_proxy_kernel.py", "--abis", abi, "--force", "--strict-hash")
                    }.result.get().assertNormalExitValue()
                } catch (failure: Exception) {
                    throw GradleException(
                        "Cannot rebuild the $abi kernel. Restore the SHA-256-matching native-kernel/prebuilt artifact, " +
                            "or install Python 3 and Go ${lock["goVersion"]} on PATH and rerun with -PrebuildKernel=true.", failure)
                }
                check(verified(destination, expected)) { "Source-built kernel checksum or size mismatch ($abi)" }
            }
        }
    }
}
tasks.named("preBuild").configure { dependsOn(prepareProxyKernel) }
