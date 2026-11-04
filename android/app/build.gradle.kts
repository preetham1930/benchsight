import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.benchsight.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.benchsight.app"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
        // Developer Mode / Mock Device Kit: attestation is not used, so both placeholders are 0
        // (Meta Wearables DAT docs). Production builds need Wearables Developer Center credentials.
        manifestPlaceholders["mwdat_application_id"] = "0"
        manifestPlaceholders["mwdat_client_token"] = "0"
    }

    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.exifinterface)
    implementation(libs.mwdat.core)
    implementation(libs.mwdat.camera)
    implementation(libs.mwdat.mockdevice)
}
