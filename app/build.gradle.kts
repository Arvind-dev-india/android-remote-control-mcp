import org.gradle.process.ExecOperations
import java.io.FileInputStream
import java.time.YearMonth
import java.util.Properties
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
    jacoco
}

/**
 * Derives the version name from `git describe` output.
 *
 * Output formats handled:
 * - "v1.2.3"              → exact tag    → "1.2.3"
 * - "v1.2.3-7-gabc1234"   → after tag    → "1.2.3-dev.7+abc1234"
 * - "v1.2.3-beta-7-g..."  → pre-release  → "1.2.3-beta-dev.7+abc1234"
 * - "abc1234"              → no tags      → "0.0.0-dev+abc1234"
 *
 * Returns null when git is unavailable or the command fails.
 */
fun getGitDescribeVersion(): String? {
    return try {
        val process =
            ProcessBuilder("git", "describe", "--tags", "--match", "v*", "--always")
                .directory(rootDir)
                .redirectErrorStream(true)
                .start()
        val output =
            process
                .inputStream
                .bufferedReader()
                .readText()
                .trim()
        val exitCode = process.waitFor()
        if (exitCode != 0 || output.isEmpty()) return null

        // Describe pattern checked first: the -N-gHASH suffix is unambiguous.
        // Using (.+) for the version part lets the regex engine backtrack correctly
        // even when pre-release segments contain hyphens (e.g. v1.0.0-rc1-3-g1234567).
        val describePattern = Regex("""^v(.+)-(\d+)-g([0-9a-f]+)$""")
        val tagPattern = Regex("""^v(\d+\.\d+\.\d+(?:-.+)?)$""")

        when {
            describePattern.matches(output) -> {
                val match = describePattern.find(output)!!
                val baseVersion = match.groupValues[1]
                val commitCount = match.groupValues[2]
                val hash = match.groupValues[3]
                "$baseVersion-dev.$commitCount+$hash"
            }

            tagPattern.matches(output) -> {
                tagPattern.find(output)!!.groupValues[1]
            }

            else -> {
                "0.0.0-dev+$output"
            }
        }
    } catch (_: Exception) {
        null
    }
}

val isExplicitVersion =
    project.gradle.startParameter
        .projectProperties
        .containsKey("VERSION_NAME")
val fallbackVersion = project.findProperty("VERSION_NAME") as String? ?: "1.0.0"
val versionNameProp =
    if (isExplicitVersion) fallbackVersion else (getGitDescribeVersion() ?: fallbackVersion)
val versionCodeProp = (project.findProperty("VERSION_CODE") as String?)?.toInt() ?: 1

ktlint {
    version.set("1.8.0")
}

android {
    namespace = "com.danielealbano.androidremotecontrolmcp"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.yedhant.androidremotecontrolmcp"
        minSdk = 33
        targetSdk = 34
        versionCode = versionCodeProp
        versionName = versionNameProp
    }

    // Release signing configuration (optional, uses keystore.properties if present)
    val keystorePropertiesFile = rootProject.file("keystore.properties")
    if (keystorePropertiesFile.exists()) {
        val keystoreProperties = Properties()
        keystoreProperties.load(FileInputStream(keystorePropertiesFile))

        signingConfigs {
            create("release") {
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
            isMinifyEnabled = false
        }
        release {
            isDebuggable = false
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        // QUERY_ALL_PACKAGES is required for app management tools (list/launch/force-stop)
        disable += "QueryAllPackagesPermission"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/io.netty.*"
            excludes += "/META-INF/LICENSE.md"
            excludes += "/META-INF/LICENSE-notice.md"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // AndroidX Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    // Material Components (XML themes)
    implementation(libs.material)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.navigation)
    debugImplementation(libs.compose.ui.tooling)

    // Lifecycle
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)

    // DataStore
    implementation(libs.datastore.preferences)

    // DocumentFile (SAF)
    implementation(libs.androidx.documentfile)

    // CameraX
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.video)

    // Google Play Services
    implementation(libs.play.services.location)

    // OpenStreetMap
    implementation(libs.osmdroid)

    // Ktor Server
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.network.tls.certificates)
    implementation(libs.ktor.serialization.kotlinx.json)

    // Ktor Client (Event Channel dispatcher — no Logging plugin, it would expose auth token)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)

    // Force patched Netty: Ktor's server engine ships netty 4.2.9, which is vulnerable.
    // Covers the HTTP Request Smuggling / HTTP/2 CONTINUATION-flood CVEs (CVE-2026-33870,
    // CVE-2026-33871) plus the native-transport advisories that the engine also pulls onto the
    // release classpath: epoll DoS (GHSA-rwm7-x88c-3g2p) and the epoll/kqueue fd leak
    // (GHSA-w573-9ffj-6ff9). All netty modules are pinned to the same version to avoid skew.
    constraints {
        implementation("io.netty:netty-codec-http:4.2.15.Final")
        implementation("io.netty:netty-codec-http2:4.2.15.Final")
        implementation("io.netty:netty-handler:4.2.15.Final")
        implementation("io.netty:netty-common:4.2.15.Final")
        implementation("io.netty:netty-buffer:4.2.15.Final")
        implementation("io.netty:netty-transport:4.2.15.Final")
        implementation("io.netty:netty-codec-base:4.2.15.Final")
        implementation("io.netty:netty-codec-compression:4.2.15.Final")
        implementation("io.netty:netty-resolver:4.2.15.Final")
        implementation("io.netty:netty-transport-native-unix-common:4.2.15.Final")
        implementation("io.netty:netty-transport-classes-epoll:4.2.15.Final")
        implementation("io.netty:netty-transport-native-epoll:4.2.15.Final")
        implementation("io.netty:netty-transport-classes-kqueue:4.2.15.Final")
        implementation("io.netty:netty-transport-native-kqueue:4.2.15.Final")
    }

    // Certificate generation (Bouncy Castle for self-signed cert with SAN support)
    implementation(libs.bouncy.castle.pkix)
    implementation(libs.bouncy.castle.prov)

    // ngrok tunnel (in-process, JNI-based) — built from source via vendor/ngrok-java submodule
    // ngrok-java: API module (interfaces, builders, Session)
    implementation(files("../vendor/ngrok-java/ngrok-java/target/ngrok-java-1.2.0-SNAPSHOT.jar"))
    // ngrok-java-native: implementation classes (NativeSession, Runtime, etc.)
    implementation(files("../vendor/ngrok-java/ngrok-java-native/target/ngrok-java-native-classes.jar"))

    // MCP SDK
    implementation(libs.mcp.kotlin.sdk.server)
    runtimeOnly(libs.slf4j.android)

    // OAuth (JWT signing/verification)
    implementation(libs.java.jwt)

    // OAuth client logos (SSRF-guarded remote image loading)
    implementation(libs.coil.compose)
    implementation(libs.coil.network)

    // Kotlinx
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // Hilt
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    // Accompanist
    implementation(libs.accompanist.permissions)

    // Unit Testing
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.junit.jupiter.params)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.bouncy.castle.pkix)
    testImplementation(libs.bouncy.castle.prov)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.mcp.kotlin.sdk.client)
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.ktor.sse)
}

dependencies {
    // ngrok-java host native library packaged as JAR for classpath-based loading.
    // Runtime.load() uses Class.getResourceAsStream() to extract the .so/.dylib,
    // so the native library must be inside a JAR on the classpath (not a loose directory).
    testRuntimeOnly(files("../vendor/ngrok-java/ngrok-java-native/target/ngrok-java-native-host.jar"))
}

// Offline IP-geolocation database, generated at build time from the CURRENT month's DB-IP City Lite
// (CC BY 4.0). The gzipped LDB1 asset is not committed (a generated artifact); it is produced into a
// generated-assets directory and registered via androidComponents so AGP wires every consumer (asset
// merge, lint-vital, etc.) to depend on it. Keyed on the year-month, so it naturally refreshes when
// DB-IP publishes a new monthly DB (up-to-date within the same month). Requires python3 + network at
// build time; the source CSV is cached under .dbip-cache (gitignored) so CI can cache the monthly download.
val generateLocationDb =
    tasks.register<GenerateLocationDbTask>("generateLocationDb") {
        script.set(rootProject.layout.projectDirectory.file("scripts/location-db/build_location_db.py"))
        month.set(YearMonth.now().toString())
        cacheDir.set(rootProject.layout.projectDirectory.dir(".dbip-cache"))
        outputDir.set(layout.buildDirectory.dir("generated/locationDb"))
    }

androidComponents {
    onVariants { variant ->
        // Registered as a generated assets source — the generation runs only for variants that package
        // assets (assemble/lint-vital), never for the unit-test path, which uses the committed fixture.
        variant.sources.assets?.addGeneratedSourceDirectory(generateLocationDb, GenerateLocationDbTask::outputDir)
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    maxHeapSize = "4g"
    // Distribute tests across all available CPU cores for faster execution.
    maxParallelForks = (Runtime.getRuntime().availableProcessors()).coerceAtLeast(1)
    // MockK uses byte-buddy/reflection internally; JDK 17 strong encapsulation
    // blocks access to these packages from unnamed modules, causing test failures.
    jvmArgs(
        "--add-opens",
        "java.base/java.lang=ALL-UNNAMED",
        "--add-opens",
        "java.base/java.lang.reflect=ALL-UNNAMED",
        "--add-opens",
        "java.base/java.util=ALL-UNNAMED",
        "--add-opens",
        "java.base/java.time=ALL-UNNAMED",
    )
}

jacoco {
    toolVersion = "0.8.14"
}

val jacocoExcludes =
    listOf(
        // Android generated
        "**/R.class",
        "**/R$*.class",
        "**/BuildConfig.*",
        "**/Manifest*.*",
        // Hilt / Dagger generated
        "**/*_HiltModules*",
        "**/*_Factory*",
        "**/*_MembersInjector*",
        "**/Hilt_*",
        "**/dagger/**",
        "**/*Module_*",
        "**/*_Impl*",
        // Compose generated
        "**/*ComposableSingletons*",
        // Android framework classes (require device/emulator, not unit-testable)
        "**/McpApplication*",
        "**/services/mcp/McpServerService*",
        "**/services/mcp/BootCompletedReceiver*",
        "**/services/screencapture/ScreenCaptureService*",
        "**/services/accessibility/McpAccessibilityService*",
        // UI layer (requires instrumented/Compose tests)
        "**/ui/**",
        // Dependency injection configuration
        "**/di/**",
    )

tasks.register<JacocoReport>("jacocoTestReport") {
    dependsOn("testDebugUnitTest")

    reports {
        html.required.set(true)
        html.outputLocation.set(layout.buildDirectory.dir("reports/jacoco/jacocoTestReport/html"))
        xml.required.set(true)
        xml.outputLocation.set(layout.buildDirectory.file("reports/jacoco/jacocoTestReport/jacocoTestReport.xml"))
        csv.required.set(false)
    }

    val debugTree =
        fileTree("${layout.buildDirectory.get()}/tmp/kotlin-classes/debug") {
            exclude(jacocoExcludes)
        }

    classDirectories.setFrom(debugTree)
    sourceDirectories.setFrom(files("src/main/kotlin"))
    executionData.setFrom(
        fileTree(layout.buildDirectory) {
            include("jacoco/testDebugUnitTest.exec")
        },
    )
}

tasks.register<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    dependsOn("jacocoTestReport")

    val debugTree =
        fileTree("${layout.buildDirectory.get()}/tmp/kotlin-classes/debug") {
            exclude(jacocoExcludes)
        }

    classDirectories.setFrom(debugTree)
    sourceDirectories.setFrom(files("src/main/kotlin"))
    executionData.setFrom(
        fileTree(layout.buildDirectory) {
            include("jacoco/testDebugUnitTest.exec")
        },
    )

    violationRules {
        rule {
            limit {
                minimum = "0.50".toBigDecimal()
            }
        }
    }
}

/**
 * Generates the compact LDB1 geolocation DB into a generated-assets directory by invoking the Python
 * builder. A proper typed task (vs a bare Exec writing into the source tree) so AGP can wire it as a
 * generated assets source with correct task dependencies. Keyed on [month] so it refreshes monthly.
 */
abstract class GenerateLocationDbTask : DefaultTask() {
    @get:InputFile
    abstract val script: RegularFileProperty

    @get:Input
    abstract val month: Property<String>

    @get:Internal
    abstract val cacheDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun generate() {
        val asset = outputDir.get().asFile.resolve("geo/location-db.bin.gz")
        asset.parentFile.mkdirs()
        execOperations.exec {
            commandLine(
                "python3",
                script.get().asFile.absolutePath,
                "--month",
                month.get(),
                "--cache-dir",
                cacheDir.get().asFile.absolutePath,
                "--out",
                asset.absolutePath,
            )
        }
    }
}
