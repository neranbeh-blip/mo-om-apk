plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mopay.customer"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.mopay.customer"
        minSdk = 26
        targetSdk = 34
        versionCode = 10
        versionName = "2.0.0"
    }
    buildFeatures { buildConfig = true }
    flavorDimensions += "brand"
    productFlavors {
        create("mtn") {
            dimension = "brand"
            applicationIdSuffix = ".mtn"
            versionNameSuffix = "-mtn"
            buildConfigField("String", "BRAND", "\"MTN\"")
            buildConfigField("String", "ACCENT_HEX", "\"#FFCC00\"")
            resValue("string", "app_name", "MoPay Customer - MTN")
        }
        create("orange") {
            dimension = "brand"
            applicationIdSuffix = ".orange"
            versionNameSuffix = "-orange"
            buildConfigField("String", "BRAND", "\"Orange\"")
            buildConfigField("String", "ACCENT_HEX", "\"#FF7900\"")
            resValue("string", "app_name", "MoPay Customer - Orange")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}
