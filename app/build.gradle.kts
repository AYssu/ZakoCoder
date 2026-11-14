import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val compatibilityAbis = providers.gradleProperty("compatibilityAbis").orElse("arm64-v8a").get()
    .split(",").map(String::trim).distinct().also { abis ->
        require(abis.isNotEmpty() && abis.all { it in setOf("arm64-v8a", "x86_64") }) { "Unsupported compatibility ABI" }
    }
val distributionEdition = providers.gradleProperty("distributionEdition").orElse("lite").get().also {
    require(it in setOf("lite", "full")) { "distributionEdition must be lite or full" }
}
val bundledDistribution = distributionEdition == "full"
val kernelLock = groovy.json.JsonSlurper().parse(rootProject.file("native-kernel/artifacts.lock.json")) as Map<*, *>
val kernelVersion = (kernelLock["upstreamTag"] ?: kernelLock["version"]) as String
val distributionAssets = layout.buildDirectory.dir("generated/distributionAssets/$distributionEdition")

val maximumVersionCode = 2_100_000_000
fun parseBuildVersionCode(value: String, property: String): Int = value.toIntOrNull().also {
    require(it != null && it in 1..maximumVersionCode) { "$property must be a positive Android version code (at most $maximumVersionCode)" }
}!!
val compatibilityVersionCode = providers.gradleProperty("compatibilityVersionCode").orNull?.let {
    parseBuildVersionCode(it, "compatibilityVersionCode")
}
val buildVersionPattern = Regex("(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)(?:-(?:beta|rc)\\.[1-9][0-9]*)?")
fun hasSupportedVersionComponents(value: String): Boolean =
    value.substringBefore('-').split('.').all { it.toIntOrNull() != null }
val baseBuildVersion = providers.gradleProperty("baseVersion").orElse("0.1.3").get().also {
    require(it.matches(buildVersionPattern) && hasSupportedVersionComponents(it)) { "baseVersion must be major.minor.patch with optional -beta.N or -rc.N" }
}
// Resolve once per Gradle invocation. CI passes both immutable values to every APK build.
val buildMoment = Instant.now()
val buildTimestamp = DateTimeFormatter.ofPattern("MMddHH").withZone(ZoneId.of("Asia/Shanghai")).format(buildMoment)
val baseVersionCore = baseBuildVersion.substringBefore('-')
val timestampedPatch = (baseVersionCore.substringAfterLast('.') + buildTimestamp).toLong().toString()
val generatedVersionName = baseVersionCore.substringBeforeLast('.') + "." + timestampedPatch +
    baseBuildVersion.substringAfter('-', "").let { if (it.isEmpty()) "" else "-$it" } + "-dev"
require(hasSupportedVersionComponents(generatedVersionName)) {
    "Timestamped version components must not exceed a signed 32-bit integer"
}
val explicitBuildVersion = providers.gradleProperty("buildVersion").orNull?.also {
    require(it.removeSuffix("-dev").matches(buildVersionPattern) && hasSupportedVersionComponents(it)) { "buildVersion must be a valid numeric version with optional beta/rc and dev suffixes" }
}
val explicitBuildVersionCode = providers.gradleProperty("buildVersionCode").orNull?.let {
    parseBuildVersionCode(it, "buildVersionCode")
}
require((explicitBuildVersion == null) == (explicitBuildVersionCode == null)) {
    "Set buildVersion and buildVersionCode together for a fixed Debug build identity"
}
require(explicitBuildVersionCode == null || compatibilityVersionCode == null) {
    "Use buildVersionCode or compatibilityVersionCode, not both"
}
val debugVersionCode = explicitBuildVersionCode ?: compatibilityVersionCode ?: run {
    val code = Duration.between(Instant.parse("2020-01-01T00:00:00Z"), buildMoment).seconds
    require(code in 1..maximumVersionCode.toLong()) { "Build time is outside the Android versionCode range" }
    code.toInt()
}
val runtimeVerification = providers.gradleProperty("runtimeVerification").orNull == "true"
val appApplicationId = if (runtimeVerification) "dev.zakocoder.app.runtimecheck" else "dev.zakocoder.app"
val kernelPluginPackage = "$appApplicationId.kernel"
val kernelPermission = "$appApplicationId.permission.KERNEL"

// Distribution builds opt in with an explicit immutable version; legacy property names remain compatible.
val betaRelease = providers.gradleProperty("betaRelease").orNull == "true"
val betaVersion = providers.gradleProperty("betaVersion").orNull
val betaVersionCode = providers.gradleProperty("betaVersionCode").orNull?.toIntOrNull()
if (betaRelease) {
    require(betaVersion?.let { it.matches(buildVersionPattern) && hasSupportedVersionComponents(it) } == true) {
        "Release requires -PbetaVersion=<major>.<minor>.<patch>[MMddHH] with optional -beta.N or -rc.N"
    }
    require(betaVersionCode != null && betaVersionCode in 1..maximumVersionCode) {
        "Release requires -PbetaVersionCode in 1..$maximumVersionCode"
    }
    require(explicitBuildVersion == null && explicitBuildVersionCode == null) {
        "Use betaVersion and betaVersionCode alone for distribution builds"
    }
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
        versionCode = if (betaRelease) requireNotNull(betaVersionCode) else debugVersionCode
        versionName = if (betaRelease) requireNotNull(betaVersion) else explicitBuildVersion ?: generatedVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["kernelPluginPackage"] = kernelPluginPackage
        manifestPlaceholders["kernelPermission"] = kernelPermission
        manifestPlaceholders["bundledKernelEnabled"] = bundledDistribution.toString()
        buildConfigField("String", "DISTRIBUTION_EDITION", "\"$distributionEdition\"")
        buildConfigField("boolean", "BUNDLED_KERNEL", bundledDistribution.toString())
        buildConfigField("boolean", "BUNDLED_ROOTFS", bundledDistribution.toString())
        buildConfigField("String", "KERNEL_VERSION", "\"$kernelVersion\"")
        buildConfigField("String", "KERNEL_PLUGIN_PACKAGE", "\"$kernelPluginPackage\"")
        buildConfigField("String", "KERNEL_PERMISSION", "\"$kernelPermission\"")
    }
    ndkVersion = "28.2.13676358"
    defaultConfig { ndk { abiFilters += compatibilityAbis } }
    sourceSets.getByName("main").jniLibs.directories.add(rootProject.file("runtime-artifacts/jniLibs").absolutePath)
    sourceSets.getByName("main").java.directories.add(rootProject.file("kernel-shared/src/main/java").absolutePath)
    sourceSets.getByName("main").assets.directories.add(distributionAssets.get().asFile.absolutePath)
    if (bundledDistribution) {
        sourceSets.getByName("main").jniLibs.directories.add(rootProject.file("native-kernel/prebuilt").absolutePath)
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += "**/libzakocoder_*.so"
            // Lite always excludes the optional kernel, including stale local cache copies.
            if (!bundledDistribution) excludes += "**/libzakocoder_xray.so"
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
// The build downloads only immutable Ubuntu archives. Installation on a Full device is offline.
// A JVM-only path keeps ordinary Android Studio builds independent of Python and Go.
val prepareDistribution = tasks.register("prepareDistribution") {
    doLast {
        val output = distributionAssets.get().asFile
        output.deleteRecursively()
        output.mkdirs()
        val manifestFile = rootProject.file("runtime/artifacts.json")
        check(manifestFile.readBytes().contentEquals(rootProject.file("app/src/main/assets/runtime-artifacts.json").readBytes())) {
            "Packaged runtime manifest differs from the source manifest"
        }
        val manifest = groovy.json.JsonSlurper().parse(manifestFile) as Map<*, *>
        if (bundledDistribution) compatibilityAbis.forEach { abi ->
            val target = if (abi == "arm64-v8a") manifest else
                (manifest["architectures"] as Map<*, *>)[abi] as Map<*, *>
            val rootfs = target["rootfs"] as Map<*, *>
            val expectedSize = (rootfs["bytes"] as Number).toLong()
            val expectedHash = rootfs["sha256"] as String
            fun valid(file: File): Boolean {
                if (!file.isFile || file.length() != expectedSize) return false
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                return digest.digest().joinToString("") { "%02x".format(it) } == expectedHash
            }
            val cache = rootProject.file("runtime-artifacts/downloads/${rootfs["version"]}.tar.xz")
            if (!valid(cache)) {
                cache.parentFile.mkdirs()
                val temporary = File.createTempFile("ubuntu-$abi-", ".part", cache.parentFile)
                try {
                    logger.lifecycle("Downloading pinned Ubuntu rootfs for Full ($abi)")
                    val connection = URI(rootfs["url"] as String).toURL().openConnection().apply {
                        connectTimeout = 30_000
                        readTimeout = 60_000
                    }
                    connection.getInputStream().use { input ->
                        temporary.outputStream().use { destination ->
                            val buffer = ByteArray(1024 * 1024)
                            var received = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                received += count
                                check(received <= expectedSize) { "Ubuntu archive exceeds pinned size ($abi)" }
                                destination.write(buffer, 0, count)
                            }
                        }
                    }
                    check(valid(temporary)) { "Ubuntu archive checksum or size mismatch ($abi)" }
                    Files.move(temporary.toPath(), cache.toPath(), StandardCopyOption.REPLACE_EXISTING)
                } finally { temporary.delete() }
            }
            cache.copyTo(output.resolve("ubuntu-rootfs/$abi.tar.xz").apply { parentFile.mkdirs() }, overwrite = true)
            val entry = (kernelLock["targets"] as Map<*, *>)[abi] as Map<*, *>
            val kernel = rootProject.file("native-kernel/prebuilt/$abi/${entry["asset"]}")
            check(kernel.isFile) { "Missing pinned kernel prebuilt ($abi)" }
            val hash = MessageDigest.getInstance("SHA-256").digest(kernel.readBytes()).joinToString("") { "%02x".format(it) }
            check(hash == entry["sha256"]) { "Kernel prebuilt checksum mismatch ($abi)" }
        }
        output.resolve("distribution.json").writeText(groovy.json.JsonOutput.toJson(mapOf(
            "edition" to distributionEdition, "abis" to compatibilityAbis,
            "bundledRootfs" to bundledDistribution, "bundledKernel" to bundledDistribution,
            "kernelVersion" to kernelVersion,
        )))
    }
}

tasks.named("preBuild").configure {
    dependsOn(prepareRuntime, prepareDistribution)
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
    implementation(libs.miuix.ui)
    implementation(libs.navigationevent.compose)
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
