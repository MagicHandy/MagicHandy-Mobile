import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.magichandy.mobile"
    // 37 is the platform installed on this PC; targetSdk follows plan §10.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.magichandy.mobile"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
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

    buildFeatures { compose = true }
    testOptions { unitTests.isIncludeAndroidResources = true }

    lint {
        warningsAsErrors = true
        abortOnError = true
        // Versions are pinned on purpose in gradle/libs.versions.toml (plan §5.5), and
        // targetSdk follows plan §10, so "newer version available" checks don't apply.
        disable +=
            setOf(
                "GradleDependency",
                "NewerVersionAvailable",
                "AndroidGradlePluginVersion",
                "OldTargetApi",
            )
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        allWarningsAsErrors = true
        optIn.add("kotlin.time.ExperimentalTime")
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":transport"))
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.browser)
    implementation(libs.kotlinx.serialization.json)
    // In-memory stores, simulated devices and a canned model, for debug builds only
    // (docs/backend/wiring.md).
    debugImplementation(testFixtures(project(":core")))
    debugImplementation(testFixtures(project(":transport")))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(testFixtures(project(":core")))
    testImplementation(testFixtures(project(":transport")))
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.archunit)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.robolectric)
    testImplementation(libs.junit.four)
    testRuntimeOnly(libs.junit.vintage)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
}
