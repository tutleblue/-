// Imported rather than written as java.util.Properties: inside a build script "java" already
// names the Java plugin extension, so the package of the same name is unreachable by that path.
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/** Signing credentials, when the machine building this has them. Never in the repository. */
val keystoreProperties: Properties? = rootProject.file("keystore.properties")
    .takeIf { it.exists() }
    ?.let { file -> Properties().apply { file.inputStream().use { stream -> load(stream) } } }

android {
    namespace = "kr.geulbeot.app"
    // API 36. Play has required it of new apps and updates since 2026-08-31, and it is what turns
    // on the behaviour this copy is built for: edge to edge with no opt-out, and no honouring a
    // fixed orientation on large screens. Neither bites here - see PLAY-BUILD.md.
    compileSdk = 36

    defaultConfig {
        applicationId = "kr.geulbeot.app"
        // Android 8.0. Below this the Storage Access Framework and the adaptive launcher icon
        // behave differently enough to need a second code path for very few devices.
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    // Korean only: the app exists to work with Korean documents and its UI follows 한글's own
    // wording. Shipping other locales would ship empty translations. This replaces
    // defaultConfig.resourceConfigurations, which AGP 8.13 no longer accepts.
    androidResources {
        localeFilters += listOf("ko")
    }

    // Release signing is read from a properties file that is deliberately not in the repository.
    // Without it the release build is simply unsigned - no key material, real or placeholder, is
    // ever committed. This is the upload key: lose it and this app can never be updated on Play
    // again, so it lives outside the source tree and is backed up separately.
    //
    // The config is created here and held in a local, rather than looked up from inside the build
    // type: a build type's DSL scope is not the android extension's, and reaching across is the
    // shape that breaks.
    val releaseSigning = keystoreProperties?.let { properties ->
        signingConfigs.create("release") {
            storeFile = rootProject.file(properties.getProperty("storeFile"))
            storePassword = properties.getProperty("storePassword")
            keyAlias = properties.getProperty("keyAlias")
            keyPassword = properties.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = releaseSigning
        }
        debug {
            // No applicationIdSuffix: the launcher shortcuts in res/xml name the package
            // explicitly, and a suffixed debug build would leave them pointing at nothing.
            versionNameSuffix = "-debug"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

dependencies {
    implementation(project(":core-hwp"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.documentfile)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.window)
    implementation(libs.androidx.compose.material.icons)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
