plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
    alias(libs.plugins.spotless)
}

// Formatting rules: plan §5.5. `./gradlew spotlessApply` formats; `check` runs spotlessCheck.
spotless {
    kotlin {
        target(
            "core/src/**/*.kt",
            "transport/src/**/*.kt",
            "app/src/**/*.kt",
            "tools/motion-review/android/*.kt",
        )
        ktfmt(libs.versions.ktfmt.get()).kotlinlangStyle()
    }
    format("kotlinImports") {
        target(
            "core/src/**/*.kt",
            "transport/src/**/*.kt",
            "app/src/**/*.kt",
            "*.gradle.kts",
            "*/*.gradle.kts",
        )
        forbidRegex(
            "explicit Kotlin imports",
            """(?m)^import\s+[\w.]+\.\*\s*$""",
            "Use explicit Kotlin imports",
        )
    }
    kotlinGradle {
        target("*.gradle.kts", "*/*.gradle.kts")
        ktfmt(libs.versions.ktfmt.get()).kotlinlangStyle()
    }
}
