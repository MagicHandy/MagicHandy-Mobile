import com.android.build.api.artifact.SingleArtifact
import groovy.json.JsonSlurper
import java.util.Properties
import java.util.zip.ZipFile
import org.gradle.work.DisableCachingByDefault
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "com.magichandy.mobile"
    // 37 is the platform installed on this PC; targetSdk follows plan §10.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.magichandy.mobile"
        minSdk = 31
        targetSdk = 36
        versionCode = 7
        versionName = "0.1.6"
    }

    val signingProperties = rootProject.layout.projectDirectory.file("keystore.properties").asFile
    if (signingProperties.isFile) {
        val values = Properties().apply { signingProperties.inputStream().use { load(it) } }
        fun required(name: String) =
            values.getProperty(name)?.takeIf { it.isNotBlank() }
                ?: throw GradleException("Missing release signing property " + name)
        fun password(name: String): String =
            values.getProperty(name + "Env")?.let { variable ->
                providers.environmentVariable(variable).orNull
                    ?: throw GradleException(
                        "Missing release signing environment variable for " + name
                    )
            } ?: required(name)
        signingConfigs.create("ownerRelease") {
            storeFile = rootProject.file(required("storeFile"))
            keyAlias = required("keyAlias")
            storePassword = password("storePassword")
            keyPassword = password("keyPassword")
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("ownerRelease")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        create("deviceTest") {
            initWith(getByName("release"))
            applicationIdSuffix = ".devicetest"
            versionNameSuffix = "-device-test"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }

    sourceSets.getByName("deviceTest").kotlin.directories.add("src/release/kotlin")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    // The language can change in App settings at runtime, so a bundle must carry every language.
    bundle { language { enableSplit = false } }
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
    // In-memory stores, simulated devices and a deterministic model, for debug builds only
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
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.preview.scanner)

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

room { schemaDirectory("$projectDir/schemas") }

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
}

/** Check the shipped DEX and resources in addition to JVM serialization and database tests. */
@DisableCachingByDefault(because = "Inspects the local APK produced by R8")
abstract class CheckReleaseContents : DefaultTask() {
    @get:InputDirectory abstract val apkDirectory: DirectoryProperty
    @get:InputFile abstract val mapping: RegularFileProperty

    @TaskAction
    fun verify() {
        val directory = apkDirectory.get().asFile
        val metadata = JsonSlurper().parse(directory.resolve("output-metadata.json")) as Map<*, *>
        val output = (metadata["elements"] as List<*>).single() as Map<*, *>
        val database = "com.magichandy.mobile.platform.storage.PlatformDatabase"
        val serializerSuffix = "$" + "$" + "serializer"
        val serializers =
            listOf(
                    "core.ports.SettingsDocument",
                    "core.ports.ModelCatalog",
                    "core.ports.SavedModel",
                    "core.ports.RunJournal",
                    "core.ports.SavedDevice",
                    "core.api.ChatSummary",
                    "core.api.ChatEntry" + '$' + "Message",
                    "core.api.ChatEntry" + '$' + "MotionStopped",
                    "core.api.Personality",
                    "core.chat.PersonaPrompt" + '$' + "Vocabulary",
                    "core.chat.PersonaPrompt" + '$' + "Voice",
                )
                .map { "com.magichandy.mobile." + it + serializerSuffix }
        val required = serializers.toSet() + database + (database + "_Impl")
        val names = mutableMapOf<String, String>()
        mapping.get().asFile.forEachLine { line ->
            if (line.startsWith("com.magichandy.mobile.") && line.endsWith(':')) {
                val original = line.substringBefore(" -> ")
                if (original in required) names[original] = line.substringAfter(" -> ").dropLast(1)
            }
        }
        check(
            names.keys == required &&
                names[database] == database &&
                names[database + "_Impl"] == database + "_Impl"
        ) {
            "Release Room lookup or serialization mapping is missing"
        }
        ZipFile(directory.resolve(output["outputFile"] as String)).use { apk ->
            for (name in
                listOf(
                    "chat/contracts.json",
                    "chat/personas.json",
                    "META-INF/magichandy/go-numerics-license.txt",
                )) check((apk.getEntry(name)?.size ?: 0) > 0) {
                "Release resource missing: " + name
            }
            val needed = names.values.map { "L" + it.replace('.', '/') + ";" }.toMutableSet()
            apk.entries()
                .asSequence()
                .filter { it.name.matches(Regex("""classes\d*[.]dex""")) }
                .forEach { entry ->
                    val dex =
                        apk.getInputStream(entry).use {
                            it.readBytes().toString(Charsets.ISO_8859_1)
                        }
                    needed.removeAll { dex.contains(it) }
                }
            check(needed.isEmpty()) {
                "Required Room or serialization class missing in release DEX"
            }
        }
    }
}

androidComponents.onVariants(androidComponents.selector().withBuildType("release")) { variant ->
    val contents =
        tasks.register<CheckReleaseContents>("checkReleaseContents") {
            apkDirectory.set(variant.artifacts.get(SingleArtifact.APK))
            mapping.set(variant.artifacts.get(SingleArtifact.OBFUSCATION_MAPPING_FILE))
        }
    tasks.named("check") { dependsOn(contents) }
}

androidComponents.onVariants(androidComponents.selector().withBuildType("deviceTest")) { variant ->
    tasks.register<CheckReleaseContents>("checkDeviceTestContents") {
        apkDirectory.set(variant.artifacts.get(SingleArtifact.APK))
        mapping.set(variant.artifacts.get(SingleArtifact.OBFUSCATION_MAPPING_FILE))
    }
}

// Use the same SDK build-tools revision AGP selected. ApkSignerTool is the apksigner CLI.
val signerJar =
    androidComponents.sdkComponents.sdkDirectory.map {
        it.file("build-tools/" + android.buildToolsVersion + "/lib/apksigner.jar")
    }

tasks.register<JavaExec>("verifyReleaseSignature") {
    classpath(signerJar)
    mainClass.set("com.android.apksigner.ApkSignerTool")
    args(
        "verify",
        "--print-certs",
        providers
            .gradleProperty("releaseApkToVerify")
            .getOrElse(
                layout.buildDirectory
                    .file("outputs/apk/release/app-release.apk")
                    .get()
                    .asFile
                    .absolutePath
            ),
    )
}

// The previews' screenshots live beside the tests, so a change to any screen shows in review.
roborazzi { outputDir.set(file("src/test/screenshots")) }
