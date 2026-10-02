plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.0"
}

android {
    namespace = "com.aura.agent"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aura.agent"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "2.2.0-global"

        val localProperties = com.android.build.gradle.internal.cxx.configure.gradleLocalProperties(rootDir)
        val apiKey = localProperties.getProperty("AURA_API_KEY", "")
        val baseUrl = localProperties.getProperty("AURA_BASE_URL", "https://api.x.ai/v1")
        val model = localProperties.getProperty("AURA_MODEL", "grok-beta")

        // فقط از local.properties — در CI بدون کلید هم build می‌شود
        buildConfigField("String", "AURA_API_KEY", "\"$apiKey\"")
        buildConfigField("String", "AURA_BASE_URL", "\"$baseUrl\"")
        buildConfigField("String", "AURA_MODEL", "\"$model\"")
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        getByName("debug") {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")

    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Secure storage
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
