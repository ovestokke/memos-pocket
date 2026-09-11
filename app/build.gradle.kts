plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val appVersionName = providers.environmentVariable("MEMOS_POCKET_VERSION_NAME").orElse("0.2.0")
val appVersionCode = providers.environmentVariable("MEMOS_POCKET_VERSION_CODE").map { value ->
    value.toIntOrNull()?.takeIf { it > 0 }
        ?: error("MEMOS_POCKET_VERSION_CODE must be a positive integer.")
}.orElse(2000)

val releaseSigningValues = mapOf(
    "storeFile" to providers.environmentVariable("MEMOS_POCKET_STORE_FILE").orNull,
    "storePassword" to providers.environmentVariable("MEMOS_POCKET_STORE_PASSWORD").orNull,
    "keyAlias" to providers.environmentVariable("MEMOS_POCKET_KEY_ALIAS").orNull,
    "keyPassword" to providers.environmentVariable("MEMOS_POCKET_KEY_PASSWORD").orNull,
)
val releaseSigningConfigured = releaseSigningValues.values.all { !it.isNullOrBlank() }
check(releaseSigningValues.values.none { !it.isNullOrBlank() } || releaseSigningConfigured) {
    "Set all MEMOS_POCKET_* signing environment variables, or none of them."
}

android {
    namespace = "com.vstokke.memos"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.vstokke.memos"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode.get()
        versionName = appVersionName.get()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = file(requireNotNull(releaseSigningValues["storeFile"]))
                storePassword = releaseSigningValues["storePassword"]
                keyAlias = releaseSigningValues["keyAlias"]
                keyPassword = releaseSigningValues["keyPassword"]
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = false
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.06.01")
    implementation(composeBom)

    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.18.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.json:json:20250107")
}
