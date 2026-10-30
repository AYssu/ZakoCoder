plugins { alias(libs.plugins.android.library) }
android {
    namespace = "com.termux.view"
    compileSdk = 37
    buildToolsVersion = "36.0.0"
    defaultConfig {
        minSdk = 26
        ndk { abiFilters += "arm64-v8a" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}
dependencies {
    implementation(libs.androidx.annotation)
    api(project(":terminal-emulator"))
    testImplementation(libs.junit)
}
