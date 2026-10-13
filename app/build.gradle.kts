plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "dev.kevtr.hilightlink"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.kevtr.hilightlink"
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "1"
        // Only the languages the app and its dialogs are shown in.
        resourceConfigurations += listOf("en", "es")
        // The Hilight LEDs only exist on 64-bit ARM Pixels.
        ndk { abiFilters += "arm64-v8a" }
    }

    // The key releases are signed with stays out of the repository. Name it in
    // ~/.gradle/gradle.properties (hilightlink.storeFile, .storePassword, .keyAlias,
    // .keyPassword); without those a release build is signed with the debug key instead.
    val releaseStore = providers.gradleProperty("hilightlink.storeFile").orNull
    signingConfigs {
        if (releaseStore != null) {
            create("release") {
                storeFile = file(releaseStore)
                storePassword = providers.gradleProperty("hilightlink.storePassword").get()
                keyAlias = providers.gradleProperty("hilightlink.keyAlias").get()
                keyPassword = providers.gradleProperty("hilightlink.keyPassword").get()
            }
        }
    }

    buildTypes {
        release {
            // R8 full mode: whole-program optimisation, shrinking and renaming.
            // proguard-rules.pro keeps the entry points that are launched by name: the root
            // helper's and the two for running inside the system.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (releaseStore != null) "release" else "debug")
        }
        debug { isMinifyEnabled = false }
        // Release without renaming, used only to record which code runs on a real phone
        // (see profile.sh). Its recording becomes src/main/baseline-prof.txt.
        create("profiling") {
            initWith(getByName("release"))
            isMinifyEnabled = false
            isShrinkResources = false
            matchingFallbacks += "release"
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
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    packaging {
        resources.excludes += listOf("META-INF/*.version", "META-INF/**/*.kotlin_module", "kotlin/**", "DebugProbesKt.bin")
    }

    sourceSets {
        named("main") { kotlin.srcDir("src/main/kotlin") }
        named("test") { kotlin.srcDir("src/test/kotlin") }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.material3)
    // Installs the bundled baseline profile so the UI is compiled ahead of time.
    implementation(libs.androidx.profileinstaller)
    // Entry point for running inside the system through an Xposed framework (system/XposedEntry).
    compileOnly(project(":xposed-stubs"))

    testImplementation(libs.junit)
    // The real org.json; the one in the unit-test android.jar is only stubs.
    testImplementation(libs.json)
}
