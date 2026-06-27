import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "dev.phonecode.app"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "dev.phonecode"
        minSdk = 26
        targetSdk = 34
        versionCode = 13
        versionName = "0.1.4"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        release {
            // R8 + resource shrinking: debug Compose is inherently janky - this is the smooth build.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Signed with the debug key so it installs directly during development.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                // Roborazzi (no Gradle plugin needed): captureRoboImage() writes PNGs whenever this
                // flag is on. Screenshots land in app/screenshots/ - the design feedback loop.
                it.systemProperty("roborazzi.test.record", "true")
            }
        }
    }

    lint {
        // lintVitalRelease crashes inside AGP 8.7's bundled lint (IncompatibleClassChangeError in
        // NonNullableMutableLiveDataDetector) against the newer Compose stack - a tooling bug, not
        // app code. Lint still runs on demand via :app:lint.
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    androidResources {
        // The bundled Alpine rootfs (alpine-aarch64.rootfs) is already gzip; store it verbatim so AGP
        // neither gunzips a `.gz` (it does, dropping the extension) nor wastefully re-compresses it.
        noCompress += "rootfs"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // busybox ships as libbusybox.so so the system permits exec; legacy packaging extracts it
        // to nativeLibraryDir on install (compressed-in-APK libs leave no on-disk file to exec).
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":agent"))
    implementation(project(":provider"))
    implementation(project(":tools"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.haze)
    implementation(libs.androidx.security.crypto)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.jgit)

    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.androidx.ui.test.junit4)
    // debugImplementation (not testImplementation): the AAR's ComponentActivity manifest entry
    // must merge into the app manifest for Robolectric to resolve createComposeRule's activity.
    debugImplementation(libs.androidx.ui.test.manifest)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
}
