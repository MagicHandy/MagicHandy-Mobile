import com.android.build.api.artifact.SingleArtifact
import groovy.json.JsonSlurper
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
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
        versionCode = 9
        versionName = "0.1.8"
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
            // Voice input's native code ships for 64-bit ARM phones only (docs/voice-design.md §8).
            ndk { abiFilters += "arm64-v8a" }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        create("deviceTest") {
            initWith(getByName("release"))
            // -PdeviceTestSuffix=.something builds it under another package, so the minified build
            // can
            // run on the emulator without touching the phone-test app's sign-ins there.
            applicationIdSuffix =
                providers.gradleProperty("deviceTestSuffix").getOrElse(".devicetest")
            versionNameSuffix = "-device-test"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
            ndk { abiFilters += "arm64-v8a" }
            proguardFile("proguard-voicelab.pro")
        }
    }

    sourceSets.getByName("deviceTest").kotlin.directories.add("src/release/kotlin")
    // The voice test page (src/voicelab) is in debug and phone-test builds, never in a release.
    for (name in listOf("debug", "deviceTest")) {
        sourceSets.getByName(name).kotlin.directories.add("src/voicelab/kotlin")
        sourceSets.getByName(name).res.directories.add("src/voicelab/res")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    packaging {
        jniLibs {
            // sherpa-onnx's JNI library needs only onnxruntime; its C and C++ APIs are unused.
            excludes += setOf("**/libsherpa-onnx-c-api.so", "**/libsherpa-onnx-cxx-api.so")
            // Compressed in the APK, about 15 MB rather than 33, and unpacked at install.
            useLegacyPackaging = true
        }
    }

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
                // Voice input's native code ships for 64-bit ARM phones only (docs/voice-design.md
                // §8). The app is for phones; ChromeOS isn't a target.
                "ChromeOsAbiSupport",
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

/** Downloads a prebuilt library once and checks its SHA-256, for one that isn't on Maven. */
@DisableCachingByDefault(because = "A download; the build cache would only store a copy")
abstract class DownloadVerifiedFile : DefaultTask() {
    @get:Input abstract val url: Property<String>
    @get:Input abstract val sha256: Property<String>
    @get:OutputFile abstract val target: RegularFileProperty

    @TaskAction
    fun download() {
        val file = target.get().asFile
        if (file.isFile && sha256Of(file) == sha256.get()) return
        val part = File(file.path + ".part")
        URI(url.get()).toURL().openStream().use { input ->
            part.outputStream().use { input.copyTo(it) }
        }
        val actual = sha256Of(part)
        check(actual == sha256.get()) { "Checksum mismatch for " + url.get() + ": " + actual }
        Files.move(part.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

// sherpa-onnx runs Silero and Parakeet for voice input and the voice test page. The SHA-256 is
// GitHub's digest for this release asset; change both together.
val sherpaOnnxAar =
    tasks.register<DownloadVerifiedFile>("downloadSherpaOnnx") {
        val version = libs.versions.sherpaOnnx.get()
        url.set(
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/v" +
                version +
                "/sherpa-onnx-" +
                version +
                ".aar"
        )
        sha256.set("4c766e2e74c9d33d595338c21fe3b305c73ba45d47e96a235675c33142a2ec50")
        target.set(layout.buildDirectory.file("third-party/sherpa-onnx-" + version + ".aar"))
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
    implementation(files(sherpaOnnxAar.flatMap { it.target }))
    // The voice test page compares ML Kit, in debug and phone-test builds only.
    for (configuration in listOf("debugImplementation", "deviceTestImplementation")) {
        add(configuration, libs.mlkit.genai.speech)
    }

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
                    "META-INF/magichandy/sherpa-onnx-license.txt",
                    "META-INF/magichandy/onnxruntime-license.txt",
                )) check((apk.getEntry(name)?.size ?: 0) > 0) {
                "Release resource missing: " + name
            }
            // Voice input's native code: arm64 only, without the unused C and C++ APIs.
            for (name in
                listOf("lib/arm64-v8a/libonnxruntime.so", "lib/arm64-v8a/libsherpa-onnx-jni.so")) {
                check(apk.getEntry(name) != null) { "Release native library missing: " + name }
            }
            val extra =
                apk.entries()
                    .asSequence()
                    .map { it.name }
                    .filter {
                        it.startsWith("lib/") &&
                            (!it.startsWith("lib/arm64-v8a/") || it.contains("sherpa-onnx-c"))
                    }
            check(extra.none()) { "Unexpected native library in release: " + extra.first() }
            // The JNI library finds sherpa-onnx's classes by name, so R8 must keep them as named.
            val needed =
                (names.values.map { "L" + it.replace('.', '/') + ";" } +
                        listOf(
                            "Lcom/k2fsa/sherpa/onnx/OfflineRecognizer;",
                            "Lcom/k2fsa/sherpa/onnx/Vad;",
                        ))
                    .toMutableSet()
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
