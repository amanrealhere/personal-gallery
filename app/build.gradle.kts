plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    namespace = "com.amanrealhere.personalgallery"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.amanrealhere.personalgallery"
        minSdk = 34
        targetSdk = 34
        versionCode = 3
        versionName = "2.1"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.compose.bom))

    implementation(libs.activity.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)

    // Glide handles original-media decoding, transformed-resource disk caching,
    // request lifecycle and in-memory reuse for the scrolling grid.
    implementation(libs.glide)
    implementation(libs.coil.compose)

    implementation(libs.paging.runtime)
    implementation(libs.paging.compose)

    implementation(libs.room.runtime)
    implementation(libs.room.paging)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)

    implementation(libs.work.runtime.ktx)
    implementation(libs.exifinterface)

    debugImplementation(libs.compose.ui.tooling)
}
