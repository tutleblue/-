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
    compileSdk = 35

    defaultConfig {
        applicationId = "kr.geulbeot.app"
        // Android 8.0. Below this the Storage Access Framework and the adaptive launcher icon
        // behave differently enough to need a second code path for very few devices.
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        // Korean only: the app exists to work with Korean documents and its UI follows
        // 한글's own wording. Shipping other locales would ship empty translations.
        resourceConfigurations += setOf("ko")
    }

    // Release signing is read from a properties file that is deliberately not in the repository.
    // Without it the release build is simply unsigned - no key material, real or placeholder, is
    // ever committed, because a key in the repository would let anyone sign a package that
    // replaces this app through its own update path.
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
