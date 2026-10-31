plugins { alias(libs.plugins.android.library) }
android {
    namespace = "com.termux.emulator"
    compileSdk = 37
    buildToolsVersion = "36.0.0"
    defaultConfig {
        minSdk = 26
        ndk { abiFilters += providers.gradleProperty("compatibilityAbis").orElse("arm64-v8a").get().split(",").map(String::trim) }
    }
    ndkVersion = "28.2.13676358"
    externalNativeBuild { ndkBuild { path = file("src/main/jni/Android.mk") } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}
dependencies {
    implementation(libs.androidx.annotation)
    testImplementation(libs.junit)
}
