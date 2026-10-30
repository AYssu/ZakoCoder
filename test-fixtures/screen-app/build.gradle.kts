plugins { id("com.android.application") version "9.5.0" }
android {
    namespace = "dev.zakocoder.screentest.fixture"
    compileSdk = 37
    buildFeatures { resValues = true }
    defaultConfig { applicationId = "dev.zakocoder.screentest.fixture"; minSdk = 26; targetSdk = 36; versionCode = 1; versionName = "1"; resValue("string", "build_marker", providers.gradleProperty("fixtureMarker").orElse("baseline").get()) }
}
