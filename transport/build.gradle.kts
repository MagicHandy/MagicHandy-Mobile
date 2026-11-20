import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `java-test-fixtures`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        allWarningsAsErrors = true
        optIn.add("kotlin.time.ExperimentalTime")
    }
}

dependencies {
    api(project(":core"))
    api(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.kotlinx.serialization.json)

    testFixturesImplementation(testFixtures(project(":core")))
    testImplementation(testFixtures(project(":core")))
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.archunit)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

kotlin.target.compilations.named("testFixtures") {
    compileTaskProvider.configure { compilerOptions.freeCompilerArgs.add("-Xexplicit-api=strict") }
}

tasks.test { useJUnitPlatform() }

sourceSets.test { resources.srcDir(rootProject.file("fixtures/wire")) }

sourceSets.test { resources.srcDir(rootProject.file("fixtures/chat")) }
