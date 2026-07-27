import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.dsl.LockMode
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.maven.MavenModule
import org.gradle.maven.MavenPomArtifact
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
}

val releaseStoreFile = providers.gradleProperty("PHONECODE_RELEASE_STORE_FILE")
    .orElse(providers.environmentVariable("PHONECODE_RELEASE_STORE_FILE"))
    .orNull
val releaseStorePassword = providers.gradleProperty("PHONECODE_RELEASE_STORE_PASSWORD")
    .orElse(providers.environmentVariable("PHONECODE_RELEASE_STORE_PASSWORD"))
    .orNull
val releaseKeyAlias = providers.gradleProperty("PHONECODE_RELEASE_KEY_ALIAS")
    .orElse(providers.environmentVariable("PHONECODE_RELEASE_KEY_ALIAS"))
    .orNull
val releaseKeyPassword = providers.gradleProperty("PHONECODE_RELEASE_KEY_PASSWORD")
    .orElse(providers.environmentVariable("PHONECODE_RELEASE_KEY_PASSWORD"))
    .orNull
val githubOauthClientId = providers.gradleProperty("PHONECODE_GITHUB_OAUTH_CLIENT_ID")
    .orElse(providers.environmentVariable("PHONECODE_GITHUB_OAUTH_CLIENT_ID"))
    .orNull
    .orEmpty()
    .trim()

fun String.asBuildConfigString(): String = "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""
val requiredAndroidNdkVersion = "28.2.13676358"

android {
    namespace = "dev.phonecode.app"
    compileSdk = 37
    buildToolsVersion = "36.0.0"
    ndkVersion = requiredAndroidNdkVersion

    defaultConfig {
        applicationId = "dev.phonecode"
        minSdk = 26
        targetSdk = 36
        versionCode = 51
        versionName = "0.5.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "GITHUB_OAUTH_CLIENT_ID", githubOauthClientId.asBuildConfigString())
        buildConfigField("boolean", "CODEX_OAUTH_ENABLED", "false")
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        if (listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword).all { it != null }) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "CODEX_OAUTH_ENABLED", "true")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            ndk {
                debugSymbolLevel = "SYMBOL_TABLE"
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        aidl = true
        buildConfig = true
        compose = true
    }

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }

    androidResources {
        noCompress += "rootfs"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // QEMU is a separate executable with sibling shared libraries, so Android must extract
            // these files to the app's native library directory before the isolated service runs it.
            useLegacyPackaging = true
        }
    }

    sourceSets.getByName("release") {
        val generatedHostRuntime = layout.buildDirectory
            .dir("generated/phonecodeReleaseHostRuntime")
            .get()
            .asFile
        jniLibs.directories.add(generatedHostRuntime.resolve("jniLibs").absolutePath)
        assets.directories.add(generatedHostRuntime.resolve("assets").absolutePath)
    }
}

dependencies {
    implementation(project(":agent"))
    implementation(project(":provider"))
    implementation(project(":tools"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
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
}

dependencyLocking {
    lockMode.set(LockMode.STRICT)
}
configurations.configureEach {
    if (name == "releaseRuntimeClasspath") {
        resolutionStrategy.activateDependencyLocking()
    }
}

val prototypeRuntimeRelativePaths = listOf(
    "assets/alpine-aarch64.rootfs",
    "jniLibs/arm64-v8a/libproot.so",
    "jniLibs/arm64-v8a/libproot-loader.so",
)
val prototypeRuntimeFiles = prototypeRuntimeRelativePaths.map { file("src/debug/$it") }
val forbiddenReleasePrototypeFiles = listOf("main", "release").flatMap { sourceSet ->
    prototypeRuntimeRelativePaths.map { file("src/$sourceSet/$it") }
}

val legalVendoredFiles = files(
    fileTree("src/main/assets") { include("*.js", "*.rootfs") },
    fileTree("src/main/jniLibs") { include("**/*.so") },
    fileTree("src/debug/assets") { include("*.js", "*.rootfs") },
    fileTree("src/debug/jniLibs") { include("**/*.so") },
    fileTree("src/release/assets") { include("**/*") },
    fileTree("src/release/jniLibs") { include("**/*.so") },
    fileTree("src/main/res/font") { include("*.ttf") },
)

data class LockedSource(
    val name: String,
    val version: String,
    val source: String,
    val digest: String,
)

fun parseSourceLock(file: File): Map<String, LockedSource> {
    val sources = file.readLines()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .map { line ->
            val fields = line.split('|')
            check(fields.size == 4 && fields.none(String::isBlank)) {
                "${file.path} has an invalid source lock entry: $line"
            }
            val source = LockedSource(fields[0], fields[1], fields[2], fields[3])
            val authenticatedDigest =
                source.digest.matches(Regex("[0-9a-f]{64}")) ||
                    source.digest.matches(Regex("[0-9a-f]{40}")) ||
                    (
                        source.name == "android-ndk" &&
                            source.source == "local Android SDK installation" &&
                            source.version == requiredAndroidNdkVersion &&
                            source.digest == requiredAndroidNdkVersion
                        )
            check(authenticatedDigest) {
                "${file.path} has an unsupported or unauthenticated digest for ${source.name}"
            }
            source
        }
    check(sources.map { it.name.lowercase() }.distinct().size == sources.size) {
        "${file.path} has duplicate component names"
    }
    return sources.associateBy { it.name.lowercase() }
}

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

val verifyLegalInventory by tasks.registering {
    val checksumFile = rootProject.file("VENDORED_CHECKSUMS")
    doLast {
        val shaPattern = Regex("[0-9a-f]{64}")
        val entries = checksumFile.readLines().filter(String::isNotBlank).associate { line ->
            val parts = line.trim().split(Regex("\\s+"), limit = 2)
            check(parts.size == 2 && parts[0].matches(shaPattern)) { "Invalid vendored checksum entry: $line" }
            parts[1] to parts[0]
        }
        val actual = legalVendoredFiles.files.mapTo(sortedSetOf()) {
            it.relativeTo(rootProject.projectDir).invariantSeparatorsPath
        }
        check(entries.keys == actual) {
            "VENDORED_CHECKSUMS does not match the vendored app inventory: registered=${entries.keys.sorted()}, actual=$actual"
        }
        entries.forEach { (path, expected) ->
            val digest = MessageDigest.getInstance("SHA-256")
            rootProject.file(path).inputStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
            check(actualHash == expected) { "$path checksum mismatch: expected $expected, got $actualHash" }
        }
        check(rootProject.file("legal/privacy.md").readBytes().contentEquals(file("src/main/assets/privacy.md").readBytes())) {
            "The public and in-app privacy policies differ"
        }
        check(rootProject.file("legal/terms.md").readBytes().contentEquals(file("src/main/assets/terms.md").readBytes())) {
            "The public and in-app terms differ"
        }
        val notices = file("src/main/assets/licenses.md").readText()
        check(rootProject.file("LICENSE").readText().contains("Apache License\n                           Version 2.0, January 2004")) {
            "The root Apache-2.0 license is missing or incomplete"
        }
        check(notices.contains("Copyright 2026 dttdrv") && notices.contains("Apache License 2.0")) {
            "The in-app PhoneCode license notice is incomplete"
        }
        check(
            listOf(
                "OpenCode", "Mermaid", "JetBrains Mono", "Alpine", "PRoot", "talloc",
                "QEMU", "GLib", "libiconv", "PCRE2", "Linux kernel", "BusyBox",
            ).all(notices::contains),
        ) {
            "The in-app open-source notice inventory is incomplete"
        }
        val thirdParty = rootProject.file("THIRD_PARTY.md").readText()
        check(thirdParty.contains("licensed under Apache License 2.0")) {
            "The third-party inventory does not identify PhoneCode's root license"
        }
        val provenance = file("src/debug/jniLibs/PROVENANCE.md").readText()
        listOf(
            "app/src/debug/jniLibs/arm64-v8a/libproot.so",
            "app/src/debug/jniLibs/arm64-v8a/libproot-loader.so",
        ).forEach { path ->
            check(provenance.contains(entries.getValue(path))) {
                "$path provenance hash does not match VENDORED_CHECKSUMS"
            }
        }
    }
}

val verifyPrototypeRuntimeBoundary by tasks.registering {
    val registry = file("src/main/kotlin/dev/phonecode/app/agent/ChatViewModel.kt")
    val bootstrap = file("src/main/kotlin/dev/phonecode/app/agent/EnvironmentBootstrap.kt")
    val hostSources = rootProject.fileTree(rootProject.projectDir) {
        include("app/src/main/**/*.kt", "agent/src/main/**/*.kt", "tools/src/main/**/*.kt")
    }
    doLast {
        check(prototypeRuntimeFiles.all(File::exists)) {
            "The bundled local runtime is incomplete"
        }
        check(listOf("ShellTool(", "ProcessTool(").all(registry.readText()::contains)) {
            "The local runtime tools are not registered"
        }
        val bootstrapText = bootstrap.readText()
        check(listOf("\"-b\", \"/data\"", "\"-b\", \"/sys\"", "listOf(\"/system/bin/sh\"").none(bootstrapText::contains)) {
            "The local runtime exposes a host path or shell fallback"
        }
        val forbiddenApis = listOf("DexClassLoader(", "InMemoryDexClassLoader(")
        val offenders = hostSources.files.filter { source ->
            forbiddenApis.any(source.readText()::contains)
        }
        check(offenders.isEmpty()) {
            "Runtime DEX loading is not allowed: ${offenders.joinToString()}"
        }
    }
}

val verifyReleaseRuntimeBoundary by tasks.registering {
    group = "verification"
    description = "Verifies that prototype PRoot and Alpine payloads are excluded from release inputs."
    inputs.files(prototypeRuntimeFiles)
    inputs.files(forbiddenReleasePrototypeFiles)
    doLast {
        check(prototypeRuntimeFiles.all(File::isFile)) {
            "The debug-only PRoot and Alpine runtime is incomplete"
        }
        val leakedFiles = forbiddenReleasePrototypeFiles.filter(File::exists)
        check(leakedFiles.isEmpty()) {
            "Prototype runtime files must not be inherited by release: ${leakedFiles.joinToString()}"
        }
    }
}

val vmFdHygieneTestBinary = layout.buildDirectory.file("native-tests/fd-hygiene-test")
val compileVmFdHygieneTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Compiles the host-side regression for QEMU descriptor remapping."
    inputs.files(
        file("src/main/jni/fd_hygiene.c"),
        file("src/main/jni/fd_hygiene.h"),
        file("src/test/native/fd_hygiene_test.c"),
    )
    outputs.file(vmFdHygieneTestBinary)
    doFirst {
        val output = vmFdHygieneTestBinary.get().asFile
        output.parentFile.mkdirs()
        commandLine(
            "cc",
            "-std=c11",
            "-Wall",
            "-Wextra",
            "-Werror",
            "-Isrc/main/jni",
            "src/main/jni/fd_hygiene.c",
            "src/test/native/fd_hygiene_test.c",
            "-o",
            output.absolutePath,
        )
    }
}

val verifyVmFdHygiene by tasks.registering(Exec::class) {
    group = "verification"
    description = "Verifies that the exec-status pipe cannot collide with QEMU's fixed descriptors."
    dependsOn(compileVmFdHygieneTest)
    inputs.file(vmFdHygieneTestBinary)
    doFirst {
        commandLine(vmFdHygieneTestBinary.get().asFile.absolutePath)
    }
}

val releaseHostEvidenceDirectory = rootProject.file("release-evidence/0.5.1/vm-host")
val releaseHostStageDirectory = layout.buildDirectory.dir("generated/phonecodeReleaseHostRuntime")
val releaseHostStageRoot = releaseHostStageDirectory.get().asFile
val prepareReleaseHostEvidence by tasks.registering(Exec::class) {
    group = "release"
    description = "Builds authenticated VM-host source and SBOM evidence from the verified offline cache."
    val script = rootProject.file("native-runtime/prepare-release-host-evidence.sh")
    inputs.files(
        script,
        rootProject.file("native-runtime/sources.lock"),
        rootProject.file("native-runtime/build-android-arm64.sh"),
        rootProject.file("native-runtime/patches/qemu-11.0.2-android.patch"),
        rootProject.file("native-runtime/patches/glib-2.88.2-phonecode.patch"),
        rootProject.file("native-runtime/out/BUILD-METADATA"),
        rootProject.file("native-runtime/out/PATCHES.sha256"),
        rootProject.file("native-runtime/out/arm64-v8a/SHA256SUMS"),
    )
    inputs.dir(rootProject.file("native-runtime/.downloads"))
    outputs.dir(releaseHostEvidenceDirectory)
    commandLine(script.absolutePath, releaseHostEvidenceDirectory.absolutePath)
}
val stageReleaseHostRuntime by tasks.registering(Exec::class) {
    group = "release"
    description = "Atomically stages the audited VM host and its authenticated release evidence."
    val script = rootProject.file("native-runtime/stage-release-host-runtime.sh")
    dependsOn(prepareReleaseHostEvidence)
    inputs.files(
        script,
        rootProject.file("native-runtime/audit-android-arm64.sh"),
        rootProject.file("native-runtime/arm64-v8a.SHA256SUMS"),
        rootProject.file("native-runtime/sources.lock"),
    )
    inputs.dir(rootProject.file("native-runtime/out/arm64-v8a"))
    inputs.dir(rootProject.file("native-runtime/out/symbols/arm64-v8a"))
    inputs.dir(rootProject.file("native-runtime/out/licenses"))
    inputs.dir(releaseHostEvidenceDirectory)
    outputs.dir(releaseHostStageDirectory)
    commandLine(
        script.absolutePath,
        releaseHostStageRoot.absolutePath,
        releaseHostEvidenceDirectory.absolutePath,
    )
}
val testReleaseHostEvidence by tasks.registering(Exec::class) {
    group = "verification"
    description = "Exercises offline host evidence generation and atomic staging against the audited runtime."
    val script = rootProject.file("native-runtime/tests/release-host-evidence-test.sh")
    inputs.files(
        script,
        rootProject.file("native-runtime/prepare-release-host-evidence.sh"),
        rootProject.file("native-runtime/stage-release-host-runtime.sh"),
    )
    commandLine(script.absolutePath)
}

val releaseNativeRuntimeDirectory = releaseHostStageRoot.resolve("jniLibs/arm64-v8a")
val releaseNativeRuntimeFiles = listOf(
    "libphonecode_qemu.so",
    "libglib-2.0.so",
    "libiconv.so",
    "libpcre2-8.so",
).map(releaseNativeRuntimeDirectory::resolve)
val releaseNativeRuntimeManifest = rootProject.file("native-runtime/arm64-v8a.SHA256SUMS")
val releaseNativeSymbolDirectory = rootProject.file("native-runtime/out/symbols/arm64-v8a")
val releaseNativeSymbolFiles = releaseNativeRuntimeFiles.map { releaseNativeSymbolDirectory.resolve(it.name) }
val releaseGuestRuntimeFiles = listOf(
    file("src/release/assets/vm/vmlinuz"),
    file("src/release/assets/vm/initramfs.cpio.gz"),
    file("src/release/assets/vm/system.img"),
    file("src/release/assets/vm/build-manifest.json"),
)
val releaseHostLicenseFiles = listOf(
    "QEMU-GPL-2.0.txt",
    "QEMU-LGPL-2.1.txt",
    "GLib-LGPL-2.1.txt",
    "libiconv-LGPL.txt",
    "PCRE2.txt",
    "libffi.txt",
    "proxy-libintl.txt",
    "dtc-GPL-2.0.txt",
    "dtc-BSD-2-Clause.txt",
).map { releaseHostStageRoot.resolve("assets/licenses/vm-host/$it") }
val releaseHostMetadataFiles = listOf(
    "SBOM.cdx.json",
    "SOURCES.lock",
    "SOURCE-MANIFEST.sha256",
).map { releaseHostStageRoot.resolve("assets/licenses/vm-host/$it") }
val releaseGuestComplianceFiles = listOf(
    "NOTICE",
    "SBOM.cdx.json",
    "SOURCES.lock",
    "PACKAGES.lock",
    "SOURCE-MANIFEST.sha256",
).map { file("src/release/assets/licenses/guest/$it") }
val releaseHostSourceDirectory = releaseHostEvidenceDirectory.resolve("sources")
val releaseGuestSourceDirectory = rootProject.file("release-evidence/0.5.1/guest/sources")
val canonicalHostSourceLock = rootProject.file("native-runtime/sources.lock")
val canonicalGuestSourceLock = rootProject.file("guest-runtime/sources.lock")
val canonicalGuestPackageLock = rootProject.file("guest-runtime/packages.lock")
val canonicalGuestBuildFiles = listOf(
    canonicalGuestSourceLock,
    canonicalGuestPackageLock,
    rootProject.file("guest-runtime/build-guest.sh"),
    rootProject.file("guest-runtime/toolchain.lock"),
    rootProject.file("guest-runtime/guest.config"),
    rootProject.file("guest-runtime/patches/series"),
)
val mermaidEvidenceGenerator = rootProject.file("legal/generate-mermaid-evidence.py")
val mermaidPackageSnapshot = rootProject.file("legal/mermaid/mermaid-10.9.6-package.json")
val mermaidAsset = file("src/main/assets/mermaid.min.js")
val mermaidReleaseEvidenceFiles = listOf(
    rootProject.file("legal/release/mermaid-PROVENANCE.json"),
    rootProject.file("legal/release/mermaid-declared-dependencies.json"),
    rootProject.file("legal/release/mermaid-SBOM.cdx.json"),
    rootProject.file("legal/release/mermaid-NOTICES.md"),
)
val releaseAppComplianceFiles = listOf(
    rootProject.file("legal/release/android-jvm-SBOM.cdx.json"),
    rootProject.file("legal/release/android-jvm-NOTICES.md"),
    rootProject.file("legal/release/JetBrainsMono-OFL-1.1.txt"),
    rootProject.file("legal/release/JetBrainsMono-PROVENANCE.json"),
) + mermaidReleaseEvidenceFiles
val remainingReleaseImplementationBlockers = listOf(
    "implement authenticated host-project workspace transport for the isolated VM runtime",
)
val remainingReleaseComplianceBlockers = listOf(
    "extract the final initramfs and system image and reconcile their actual package inventory",
    "prove the exact bundled Mermaid dependency closure and complete every required third-party notice",
    "reconcile source-level copyright and NOTICE obligations for runtime components",
)
val remainingPlaySubmissionBlockers = listOf(
    "audit the complete signed AAB native graph and upload native debug symbols",
    "complete signed-device VM lifecycle and Play artifact evidence",
)
val androidComponentsExtension = extensions.getByType<ApplicationAndroidComponentsExtension>()

fun cycloneDxComponents(file: File): List<Map<*, *>> {
    val document = JsonSlurper().parse(file) as? Map<*, *>
        ?: error("${file.path} is not a JSON object")
    check(document["bomFormat"] == "CycloneDX") { "${file.path} is not a CycloneDX SBOM" }
    check(document["specVersion"] is String) { "${file.path} has no CycloneDX specVersion" }
    val components = document["components"] as? Collection<*>
        ?: error("${file.path} has no component inventory")
    val parsed = components.map {
        it as? Map<*, *> ?: error("${file.path} has a non-object component")
    }
    parsed.forEach { component ->
        check(
            component["name"] is String &&
                component["version"] is String &&
                (component["version"] as String).isNotBlank(),
        ) {
            "${file.path} has a component without an exact name and version"
        }
        val licenses = component["licenses"] as? Collection<*>
        val hasConcreteLicense = !licenses.isNullOrEmpty() && licenses.all { value ->
            val entry = value as? Map<*, *> ?: return@all false
            val expression = entry["expression"] as? String
            val license = entry["license"] as? Map<*, *>
            expression?.isNotBlank() == true ||
                (license?.get("id") as? String)?.isNotBlank() == true ||
                (license?.get("name") as? String)?.isNotBlank() == true
        }
        check(hasConcreteLicense) {
            "${file.path} component ${component["name"]} has no license declaration"
        }
    }
    return parsed
}

fun validateCycloneDxCoordinates(file: File, requiredCoordinates: Set<String>) {
    val coordinates = cycloneDxComponents(file).mapNotNull { component ->
        val group = component["group"] as? String ?: return@mapNotNull null
        "$group:${component["name"]}:${component["version"]}"
    }.toSet()
    check(coordinates.containsAll(requiredCoordinates)) {
        "${file.path} does not cover the resolved release graph: " +
            (requiredCoordinates - coordinates).sorted().joinToString()
    }
}

fun validateCycloneDxLock(file: File, lock: Map<String, LockedSource>, requireExactInventory: Boolean) {
    val components = cycloneDxComponents(file)
    val indexed = components.associateBy { (it["name"] as String).lowercase() }
    check(indexed.keys.containsAll(lock.keys)) {
        "${file.path} does not cover locked components: ${(lock.keys - indexed.keys).sorted().joinToString()}"
    }
    if (requireExactInventory) {
        check(indexed.keys == lock.keys) {
            "${file.path} and the component lock have different inventories"
        }
    }
    lock.forEach { (name, locked) ->
        val component = indexed.getValue(name)
        check(component["version"] == locked.version) {
            "${file.path} has the wrong version for ${locked.name}"
        }
        val expectedAlgorithm = when {
            locked.digest.matches(Regex("[0-9a-f]{64}")) -> "SHA-256"
            locked.digest.matches(Regex("[0-9a-f]{40}")) -> "SHA-1"
            else -> null
        }
        if (expectedAlgorithm != null) {
            val hashes = component["hashes"] as? Collection<*>
            val hasLockedHash = hashes.orEmpty().any { value ->
                val hash = value as? Map<*, *> ?: return@any false
                hash["alg"] == expectedAlgorithm && hash["content"] == locked.digest
            }
            check(hasLockedHash) {
                "${file.path} does not bind ${locked.name} to its locked source digest"
            }
        }
    }
}

fun validateSourceManifest(file: File, sourceDirectory: File) {
    val lines = file.readLines().filter(String::isNotBlank)
    val entry = Regex("[0-9a-f]{64}  [^/\\s][^\\s]*")
    check(lines.isNotEmpty() && lines.all(entry::matches)) { "${file.path} is not a SHA-256 source manifest" }
    val entries = lines.associate { it.substring(66) to it.substring(0, 64) }
    check(entries.size == lines.size) { "${file.path} has duplicate paths" }
    check(entries.keys.all { path -> '\\' !in path && path.split('/').none { it == ".." } }) {
        "${file.path} contains an unsafe source path"
    }
    check(sourceDirectory.isDirectory) { "${sourceDirectory.path} is not a source evidence directory" }
    val actual = sourceDirectory.walkTopDown()
        .filter(File::isFile)
        .associate { it.relativeTo(sourceDirectory).invariantSeparatorsPath to sha256(it) }
    check(entries == actual) {
        "${file.path} does not exactly authenticate the published source evidence directory"
    }
}

fun validateLockedSourceArchives(lock: Map<String, LockedSource>, sourceDirectory: File) {
    lock.values.forEach { locked ->
        when {
            locked.digest.matches(Regex("[0-9a-f]{64}")) -> {
                val archiveName = locked.source.substringAfterLast('/')
                val matches = sourceDirectory.walkTopDown().filter { it.isFile && it.name == archiveName }.toList()
                check(matches.size == 1 && sha256(matches.single()) == locked.digest) {
                    "${sourceDirectory.path} does not contain the exact locked source archive for ${locked.name}"
                }
            }
            locked.digest.matches(Regex("[0-9a-f]{40}")) -> {
                val bundle = sourceDirectory.resolve("git/${locked.name}.bundle")
                check(bundle.isFile) {
                    "${sourceDirectory.path} has no standalone Git bundle for ${locked.name}"
                }
                val verification = ProcessBuilder("git", "bundle", "verify", bundle.absolutePath)
                    .directory(sourceDirectory)
                    .redirectErrorStream(true)
                    .start()
                val verificationOutput = verification.inputStream.bufferedReader().readText()
                check(verification.waitFor() == 0) {
                    "${bundle.path} is not a complete Git bundle: $verificationOutput"
                }
                val heads = ProcessBuilder("git", "bundle", "list-heads", bundle.absolutePath)
                    .directory(sourceDirectory)
                    .redirectErrorStream(true)
                    .start()
                val headsOutput = heads.inputStream.bufferedReader().readText()
                check(heads.waitFor() == 0 && headsOutput.lineSequence().any { it.startsWith("${locked.digest} ") }) {
                    "${bundle.path} does not advertise locked commit ${locked.digest}"
                }
            }
            else -> check(
                locked.name == "android-ndk" &&
                    locked.source == "local Android SDK installation" &&
                    locked.version == requiredAndroidNdkVersion &&
                    locked.digest == requiredAndroidNdkVersion,
            ) {
                "${locked.name} uses an unsupported source authentication scheme"
            }
        }
    }
}

fun validatePublishedBuildInputs(sourceDirectory: File) {
    val requiredInputs = listOf(
        rootProject.file("native-runtime/sources.lock") to "phonecode/native-runtime/sources.lock",
        rootProject.file("native-runtime/build-android-arm64.sh") to
            "phonecode/native-runtime/build-android-arm64.sh",
        rootProject.file("native-runtime/patches/qemu-11.0.2-android.patch") to
            "phonecode/native-runtime/patches/qemu-11.0.2-android.patch",
        rootProject.file("native-runtime/patches/glib-2.88.2-phonecode.patch") to
            "phonecode/native-runtime/patches/glib-2.88.2-phonecode.patch",
    )
    requiredInputs.forEach { (committed, relativePath) ->
        val published = sourceDirectory.resolve(relativePath)
        check(published.isFile && published.readBytes().contentEquals(committed.readBytes())) {
            "${sourceDirectory.path} does not contain exact build input $relativePath"
        }
    }
}

val verifyReleaseHostEvidence by tasks.registering {
    group = "verification"
    description = "Validates the staged VM-host SBOM, source lock, archives, Git bundles, and build inputs."
    dependsOn(stageReleaseHostRuntime)
    inputs.files(releaseHostMetadataFiles, canonicalHostSourceLock)
    inputs.dir(releaseHostSourceDirectory)
    doLast {
        val packagedHostLock = releaseHostMetadataFiles.first { it.name == "SOURCES.lock" }
        check(packagedHostLock.readBytes().contentEquals(canonicalHostSourceLock.readBytes())) {
            "The packaged VM host source lock differs from native-runtime/sources.lock"
        }
        val hostLock = parseSourceLock(canonicalHostSourceLock)
        val hostRuntimeNames = setOf("qemu", "glib", "libiconv", "pcre2", "libffi", "proxy-libintl", "dtc")
        val hostRuntimeLock = hostLock.filterKeys(hostRuntimeNames::contains)
        check(hostRuntimeLock.keys == hostRuntimeNames) { "native-runtime/sources.lock is incomplete" }
        validateCycloneDxLock(
            releaseHostMetadataFiles.first { it.name == "SBOM.cdx.json" },
            hostRuntimeLock,
            requireExactInventory = true,
        )
        validateSourceManifest(
            releaseHostMetadataFiles.first { it.name == "SOURCE-MANIFEST.sha256" },
            releaseHostSourceDirectory,
        )
        validateLockedSourceArchives(hostLock, releaseHostSourceDirectory)
        validatePublishedBuildInputs(releaseHostSourceDirectory)
    }
}

fun validatePublishedGuestBuildInputs(sourceDirectory: File, canonicalFiles: List<File>) {
    canonicalFiles.forEach { committed ->
        val relativePath = committed.relativeTo(rootProject.projectDir).invariantSeparatorsPath
        val published = sourceDirectory.resolve("phonecode/$relativePath")
        check(published.isFile && published.readBytes().contentEquals(committed.readBytes())) {
            "${sourceDirectory.path} does not contain exact guest build input phonecode/$relativePath"
        }
    }
    val patchDirectory = rootProject.file("guest-runtime/patches")
    val declaredPatches = rootProject.file("guest-runtime/patches/series")
        .readLines()
        .filter { it.isNotBlank() && !it.startsWith("#") }
    check(declaredPatches.distinct().size == declaredPatches.size) {
        "guest-runtime/patches/series has duplicate patches"
    }
    check(declaredPatches.all { '/' !in it && '\\' !in it && it != "." && it != ".." }) {
        "guest-runtime/patches/series contains an unsafe patch path"
    }
    val actualPatches = patchDirectory.listFiles().orEmpty()
        .filter { it.isFile && it.name != "series" }
        .mapTo(sortedSetOf()) { it.name }
    check(actualPatches == declaredPatches.toSortedSet()) {
        "guest-runtime/patches/series does not exactly cover the guest patch directory"
    }
    actualPatches.forEach { patchName ->
        val committed = patchDirectory.resolve(patchName)
        val published = sourceDirectory.resolve("phonecode/guest-runtime/patches/$patchName")
        check(published.isFile && published.readBytes().contentEquals(committed.readBytes())) {
            "${sourceDirectory.path} does not contain exact guest patch $patchName"
        }
    }
}

fun validateNoticeCoordinates(file: File, coordinates: Set<String>) {
    val notices = file.readText()
    check(coordinates.all(notices::contains)) {
        "${file.path} does not identify every resolved component and version"
    }
}

fun releaseExternalArtifacts() = configurations.getByName("releaseRuntimeClasspath")
    .resolvedConfiguration
    .resolvedArtifacts
    .filter { it.id.componentIdentifier is ModuleComponentIdentifier }
    .distinctBy {
        val module = it.moduleVersion.id
        "${module.group}:${module.name}:${module.version}|${it.file.canonicalPath}"
    }

val releaseDependencyEvidenceInput =
    layout.buildDirectory.file("intermediates/releaseDependencyEvidence/resolved-artifacts.json")
val releaseDependencyLock = file("gradle.lockfile")
val androidJvmSbom = releaseAppComplianceFiles.first { it.name == "android-jvm-SBOM.cdx.json" }
val androidJvmNotices = releaseAppComplianceFiles.first { it.name == "android-jvm-NOTICES.md" }
val androidJvmSupplementalLicenses = rootProject.fileTree("legal/upstream") {
    include(
        "slf4j-1.7.36-LICENSE.txt",
        "slf4j-1.7.36-LICENSE.provenance.json",
        "kotlinx-atomicfu-0.23.2-NOTICE.txt",
        "kotlinx-atomicfu-0.23.2-NOTICE.provenance.json",
        "kotlinx-coroutines-1.9.0-NOTICE.txt",
        "kotlinx-coroutines-1.9.0-NOTICE.provenance.json",
        "kotlinx-serialization-1.7.3-NOTICE.txt",
        "kotlinx-serialization-1.7.3-NOTICE.provenance.json",
        "kotlin-stdlib-2.3.21-THREETENBP-LICENSE.txt",
        "kotlin-stdlib-2.3.21-THREETENBP-LICENSE.provenance.json",
    )
}
val writeReleaseDependencyEvidenceInput by tasks.registering {
    group = "release"
    description = "Resolves the exact external release artifacts and their Maven POM provenance."
    inputs.files(configurations.named("releaseRuntimeClasspath"))
    inputs.file(releaseDependencyLock)
    outputs.file(releaseDependencyEvidenceInput)
    doLast {
        check(releaseDependencyLock.isFile) {
            "Dependency lock is missing; write and review app/gradle.lockfile before generating release evidence"
        }
        val artifacts = releaseExternalArtifacts()
        check(artifacts.isNotEmpty()) { "The external release runtime graph is empty" }
        val duplicateCoordinates = artifacts
            .groupBy {
                val module = it.moduleVersion.id
                "${module.group}:${module.name}:${module.version}"
            }
            .filterValues { it.size > 1 }
        check(duplicateCoordinates.isEmpty()) {
            "The release runtime graph has multiple artifacts for one coordinate: " +
                duplicateCoordinates.mapValues { (_, values) -> values.map { it.file.name }.sorted() }
        }
        val componentIds = artifacts
            .map { it.id.componentIdentifier as ModuleComponentIdentifier }
            .toSet()
        val pomQuery = dependencies.createArtifactResolutionQuery()
            .forComponents(componentIds)
            .withArtifacts(MavenModule::class.java, MavenPomArtifact::class.java)
            .execute()
        val poms = pomQuery.resolvedComponents.associate { component ->
            val id = component.id as ModuleComponentIdentifier
            val coordinate = "${id.group}:${id.module}:${id.version}"
            val resolvedPoms = component.getArtifacts(MavenPomArtifact::class.java)
                .filterIsInstance<ResolvedArtifactResult>()
            check(resolvedPoms.size == 1) {
                "Expected one Maven POM for $coordinate, found ${resolvedPoms.size}"
            }
            coordinate to resolvedPoms.single().file
        }
        val records = artifacts.map { artifact ->
            val module = artifact.moduleVersion.id
            val coordinate = "${module.group}:${module.name}:${module.version}"
            mapOf(
                "coordinate" to coordinate,
                "artifact" to artifact.file.absolutePath,
                "pom" to checkNotNull(poms[coordinate]) { "No resolved Maven POM for $coordinate" }.absolutePath,
            )
        }.sortedBy { it.getValue("coordinate") }
        val output = releaseDependencyEvidenceInput.get().asFile
        output.parentFile.mkdirs()
        output.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(records)) + "\n")
    }
}
val prepareAndroidJvmReleaseEvidence by tasks.registering(Exec::class) {
    group = "release"
    description = "Generates exact releaseRuntimeClasspath CycloneDX and coordinate notice evidence."
    val script = rootProject.file("legal/generate-android-jvm-evidence.py")
    dependsOn(writeReleaseDependencyEvidenceInput)
    inputs.files(
        script,
        releaseDependencyEvidenceInput,
        releaseDependencyLock,
        androidJvmSupplementalLicenses,
    )
    outputs.files(androidJvmSbom, androidJvmNotices)
    commandLine(
        script.absolutePath,
        releaseDependencyEvidenceInput.get().asFile.absolutePath,
        androidJvmSbom.parentFile.absolutePath,
    )
}
val testAndroidJvmReleaseEvidence by tasks.registering(Exec::class) {
    group = "verification"
    description = "Checks deterministic Android/JVM SBOM generation, POM inheritance, and embedded legal files."
    val script = rootProject.file("legal/tests/android-jvm-evidence-test.sh")
    inputs.files(
        script,
        rootProject.file("legal/generate-android-jvm-evidence.py"),
        androidJvmSupplementalLicenses,
    )
    commandLine(script.absolutePath)
}
val verifyAndroidJvmReleaseEvidence by tasks.registering {
    group = "verification"
    description = "Authenticates the exact locked external release graph against its SBOM and notices."
    dependsOn(prepareAndroidJvmReleaseEvidence)
    inputs.files(
        androidJvmSbom,
        androidJvmNotices,
        releaseDependencyLock,
        androidJvmSupplementalLicenses,
    )
    inputs.files(configurations.named("releaseRuntimeClasspath"))
    doLast {
        val artifacts = releaseExternalArtifacts()
        val expected = artifacts.associate { artifact ->
            val module = artifact.moduleVersion.id
            "${module.group}:${module.name}:${module.version}" to sha256(artifact.file)
        }
        check(expected.isNotEmpty()) { "The external release runtime graph is empty" }
        val components = cycloneDxComponents(androidJvmSbom)
        val actual = components.associate { component ->
            val coordinate = "${component["group"]}:${component["name"]}:${component["version"]}"
            val hashes = component["hashes"] as? Collection<*>
                ?: error("$coordinate has no artifact hashes")
            val artifactHash = hashes.mapNotNull { value ->
                val hash = value as? Map<*, *> ?: return@mapNotNull null
                if (hash["alg"] == "SHA-256") hash["content"] as? String else null
            }.singleOrNull() ?: error("$coordinate does not have exactly one SHA-256 artifact hash")
            val properties = component["properties"] as? Collection<*>
                ?: error("$coordinate has no evidence properties")
            val evidence = properties.mapNotNull { value ->
                val property = value as? Map<*, *> ?: return@mapNotNull null
                if (property["name"] == "phonecode:license-evidence") property["value"] as? String else null
            }.singleOrNull()
            check(
                evidence in setOf(
                    "upstream-pom-declaration",
                    "inherited-upstream-pom-declaration",
                    "missing-upstream-pom-declaration",
                ),
            ) {
                "$coordinate does not state its Maven POM license-evidence status"
            }
            check(evidence != "missing-upstream-pom-declaration") {
                "$coordinate has no license declaration in its authenticated Maven POM ancestry"
            }
            val pomEvidenceChain = properties.mapNotNull { value ->
                val property = value as? Map<*, *> ?: return@mapNotNull null
                if (property["name"] == "phonecode:pom-evidence-chain") property["value"] as? String else null
            }.singleOrNull()
            check(!pomEvidenceChain.isNullOrBlank()) {
                "$coordinate has no hashed Maven POM evidence chain"
            }
            val embeddedLegalFileCount = properties.mapNotNull { value ->
                val property = value as? Map<*, *> ?: return@mapNotNull null
                if (property["name"] == "phonecode:embedded-legal-files-included") {
                    (property["value"] as? String)?.toIntOrNull()
                } else {
                    null
                }
            }.singleOrNull()
            check(embeddedLegalFileCount != null && embeddedLegalFileCount >= 0) {
                "$coordinate has no embedded legal-file inventory"
            }
            val supplementalLegalFileCount = properties.mapNotNull { value ->
                val property = value as? Map<*, *> ?: return@mapNotNull null
                if (property["name"] == "phonecode:supplemental-legal-files-included") {
                    (property["value"] as? String)?.toIntOrNull()
                } else {
                    null
                }
            }.singleOrNull()
            check(supplementalLegalFileCount != null && supplementalLegalFileCount >= 0) {
                "$coordinate has no supplemental legal-file inventory"
            }
            val completeLicenseText = properties.mapNotNull { value ->
                val property = value as? Map<*, *> ?: return@mapNotNull null
                if (property["name"] == "phonecode:complete-license-text-included") {
                    property["value"] as? String
                } else {
                    null
                }
            }.singleOrNull()
            check(completeLicenseText in setOf("true", "false")) {
                "$coordinate does not state complete-license-text coverage"
            }
            check(completeLicenseText == "true") {
                "$coordinate has no complete license text in the generated Android/JVM notices"
            }
            coordinate to artifactHash
        }
        check(actual == expected) {
            "Android/JVM SBOM differs from the exact release runtime graph: " +
                "missing=${(expected.keys - actual.keys).sorted()}, extra=${(actual.keys - expected.keys).sorted()}"
        }
        validateNoticeCoordinates(androidJvmNotices, expected.keys)
        val noticeText = androidJvmNotices.readText()
        check(noticeText.contains("does not establish complete copyright or license-text coverage")) {
            "Android/JVM notices falsely omit the incomplete-license-evidence warning"
        }
        val lockedCoordinates = releaseDependencyLock.readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") && '=' in it }
            .map { it.substringBefore('=') }
            .toSet()
        check(lockedCoordinates.containsAll(expected.keys)) {
            "app/gradle.lockfile does not lock the complete external release runtime graph"
        }
    }
}
val jetBrainsMonoEvidenceValidator =
    rootProject.file("legal/verify-jetbrains-mono-evidence.py")
val jetBrainsMonoLicense =
    rootProject.file("legal/release/JetBrainsMono-OFL-1.1.txt")
val jetBrainsMonoProvenance =
    rootProject.file("legal/release/JetBrainsMono-PROVENANCE.json")
val jetBrainsMonoFonts = fileTree("src/main/res/font") {
    include("jetbrainsmono_*.ttf")
}
val testJetBrainsMonoReleaseEvidence by tasks.registering(Exec::class) {
    group = "verification"
    description = "Tests exact JetBrains Mono revision, font-byte, metadata, and OFL authentication."
    val testScript = rootProject.file("legal/tests/jetbrains-mono-evidence-test.sh")
    inputs.files(
        testScript,
        jetBrainsMonoEvidenceValidator,
        jetBrainsMonoLicense,
        jetBrainsMonoProvenance,
        jetBrainsMonoFonts,
    )
    commandLine("bash", testScript.absolutePath)
}
val verifyJetBrainsMonoReleaseEvidence by tasks.registering(Exec::class) {
    group = "verification"
    description = "Authenticates bundled JetBrains Mono files against an immutable official revision."
    inputs.files(
        jetBrainsMonoEvidenceValidator,
        jetBrainsMonoLicense,
        jetBrainsMonoProvenance,
        jetBrainsMonoFonts,
    )
    commandLine(
        "python3",
        jetBrainsMonoEvidenceValidator.absolutePath,
        "--font-dir",
        file("src/main/res/font").absolutePath,
        "--license",
        jetBrainsMonoLicense.absolutePath,
        "--provenance",
        jetBrainsMonoProvenance.absolutePath,
    )
}
val testMermaidReleaseEvidence by tasks.registering(Exec::class) {
    group = "verification"
    description = "Checks deterministic Mermaid provenance and fail-closed incomplete-closure evidence."
    val testScript = rootProject.file("legal/tests/mermaid-evidence-test.sh")
    inputs.files(
        testScript,
        mermaidEvidenceGenerator,
        mermaidPackageSnapshot,
        mermaidAsset,
        mermaidReleaseEvidenceFiles,
    )
    commandLine(testScript.absolutePath)
}
val verifyMermaidReleaseEvidence by tasks.registering(Exec::class) {
    group = "verification"
    description = "Authenticates the pinned Mermaid asset without claiming a complete dependency closure."
    inputs.files(
        mermaidEvidenceGenerator,
        mermaidPackageSnapshot,
        mermaidAsset,
        mermaidReleaseEvidenceFiles,
    )
    commandLine(
        mermaidEvidenceGenerator.absolutePath,
        "verify",
        mermaidAsset.absolutePath,
        mermaidPackageSnapshot.absolutePath,
        mermaidReleaseEvidenceFiles.first().parentFile.absolutePath,
    )
}

val releaseBundle = layout.buildDirectory.file("outputs/bundle/release/app-release.aab")
val playSubmissionEvidenceManifest = rootProject.file("play/0.5.1/submission-evidence.json")
val playSubmissionEvidenceValidator = rootProject.file("play/verify_submission_evidence.py")
val verifyPlaySubmissionEvidenceSchema by tasks.registering(Exec::class) {
    group = "verification"
    description = "Validates the fail-closed Play submission evidence draft without claiming readiness."
    inputs.files(playSubmissionEvidenceManifest, playSubmissionEvidenceValidator)
    commandLine(
        "python3",
        playSubmissionEvidenceValidator.absolutePath,
        "--schema-only",
        playSubmissionEvidenceManifest.absolutePath,
    )
}
val verifyPlaySubmissionEvidence by tasks.registering(Exec::class) {
    group = "verification"
    description = "Requires every Play policy item to pass with evidence bound to the exact release AAB."
    dependsOn("bundleRelease")
    inputs.files(playSubmissionEvidenceManifest, playSubmissionEvidenceValidator, releaseBundle)
    commandLine(
        "python3",
        playSubmissionEvidenceValidator.absolutePath,
        "--aab",
        releaseBundle.get().asFile.absolutePath,
        playSubmissionEvidenceManifest.absolutePath,
    )
}
val verifyPlayRelease by tasks.registering(Exec::class) {
    group = "verification"
    description = "Verifies credentials and the audited native runtime required by Play release artifacts."
    inputs.files(
        releaseNativeRuntimeFiles,
        releaseNativeSymbolFiles,
        releaseGuestRuntimeFiles,
        releaseHostLicenseFiles,
        releaseHostMetadataFiles,
        releaseGuestComplianceFiles,
        releaseAppComplianceFiles,
        canonicalHostSourceLock,
        canonicalGuestBuildFiles,
        releaseHostSourceDirectory,
        releaseGuestSourceDirectory,
    )
    inputs.file(releaseNativeRuntimeManifest)
    environment(
        "ANDROID_NDK_HOME",
        androidComponentsExtension.sdkComponents.ndkDirectory.get().asFile.absolutePath,
    )
    commandLine(
        rootProject.file("native-runtime/audit-android-arm64.sh").absolutePath,
        releaseNativeRuntimeDirectory.absolutePath,
        releaseNativeRuntimeManifest.absolutePath,
        releaseNativeSymbolDirectory.absolutePath,
    )
    dependsOn(
        verifyReleaseHostEvidence,
        verifyAndroidJvmReleaseEvidence,
        verifyJetBrainsMonoReleaseEvidence,
        verifyMermaidReleaseEvidence,
        verifyReleaseRuntimeBoundary,
        verifyLegalInventory,
    )

    doFirst {
        val previousBundle = releaseBundle.get().asFile
        check(!previousBundle.exists() || previousBundle.delete()) {
            "Could not remove stale release bundle ${previousBundle.path} before verification"
        }
        val blockers = buildList {
            if (githubOauthClientId.isBlank() || githubOauthClientId == "178c6fc778ccc68e1d6a") {
                add("configure PHONECODE_GITHUB_OAUTH_CLIENT_ID with a PhoneCode-owned GitHub OAuth app")
            }
            if (listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword).any { it.isNullOrBlank() }) {
                add("configure all PHONECODE_RELEASE_* upload-signing credentials")
            } else if (!file(releaseStoreFile!!).isFile) {
                add("PHONECODE_RELEASE_STORE_FILE does not identify a readable upload keystore")
            }
            val missingRuntimeFiles = releaseNativeRuntimeFiles.filterNot(File::isFile)
            if (missingRuntimeFiles.isNotEmpty()) {
                add(
                    "stage the audited release runtime under " +
                        "app/build/generated/phonecodeReleaseHostRuntime/jniLibs/arm64-v8a " +
                        "(missing: ${missingRuntimeFiles.joinToString { it.name }})",
                )
            }
            val missingSymbolFiles = releaseNativeSymbolFiles.filterNot(File::isFile)
            if (missingSymbolFiles.isNotEmpty()) {
                add(
                    "build the matching unstripped native runtime symbols " +
                        "(missing: ${missingSymbolFiles.joinToString { it.name }})",
                )
            }
            val missingGuestFiles = releaseGuestRuntimeFiles.filterNot(File::isFile)
            if (missingGuestFiles.isNotEmpty()) {
                add(
                    "package the reproducible licensed guest runtime under app/src/release/assets/vm " +
                        "(missing: ${missingGuestFiles.joinToString { it.name }})",
                )
            }
            val missingComplianceFiles =
                (
                    releaseHostLicenseFiles + releaseHostMetadataFiles + releaseGuestComplianceFiles +
                        releaseAppComplianceFiles + canonicalGuestBuildFiles
                    )
                    .filterNot(File::isFile)
            if (missingComplianceFiles.isNotEmpty()) {
                add(
                    "complete the release license, notice, SBOM, provenance, and source-lock evidence " +
                        "(missing: ${missingComplianceFiles.joinToString { it.relativeTo(rootProject.projectDir).path }})",
                )
            }
            val missingSourceDirectories =
                listOf(releaseHostSourceDirectory, releaseGuestSourceDirectory).filterNot(File::isDirectory)
            if (missingSourceDirectories.isNotEmpty()) {
                add(
                    "publish the authenticated host and guest corresponding-source bundles " +
                        "(missing: ${missingSourceDirectories.joinToString { it.relativeTo(rootProject.projectDir).path }})",
                )
            }
            if (missingComplianceFiles.isEmpty()) {
                val androidSbom = releaseAppComplianceFiles.first { it.name == "android-jvm-SBOM.cdx.json" }
                val androidNotices = releaseAppComplianceFiles.first { it.name == "android-jvm-NOTICES.md" }
                val releaseCoordinates = releaseExternalArtifacts()
                    .map { artifact ->
                        val module = artifact.moduleVersion.id
                        "${module.group}:${module.name}:${module.version}"
                    }
                    .toSet()
                check(releaseCoordinates.isNotEmpty()) { "The resolved release runtime graph is empty" }
                validateCycloneDxCoordinates(androidSbom, releaseCoordinates)
                validateNoticeCoordinates(androidNotices, releaseCoordinates)

                val fontLicense = releaseAppComplianceFiles.first { it.name == "JetBrainsMono-OFL-1.1.txt" }
                val fontLicenseText = fontLicense.readText()
                check(
                    fontLicense.length() >= 4_000 &&
                        fontLicenseText.contains("SIL OPEN FONT LICENSE Version 1.1") &&
                        fontLicenseText.contains("PREAMBLE"),
                ) {
                    "${fontLicense.path} is not the complete OFL-1.1 text"
                }
                val fontProvenanceFile =
                    releaseAppComplianceFiles.first { it.name == "JetBrainsMono-PROVENANCE.json" }
                val fontProvenance = JsonSlurper().parse(fontProvenanceFile) as? Map<*, *>
                    ?: error("${fontProvenanceFile.path} is not a JSON object")
                check((fontProvenance["version"] as? String).orEmpty().isNotBlank()) {
                    "${fontProvenanceFile.path} has no exact JetBrains Mono version"
                }
                check((fontProvenance["sourceUrl"] as? String).orEmpty().startsWith("https://")) {
                    "${fontProvenanceFile.path} has no HTTPS upstream source"
                }
                check((fontProvenance["copyright"] as? String).orEmpty().isNotBlank()) {
                    "${fontProvenanceFile.path} has no copyright statement"
                }
                val fontHashes = fontProvenance["files"] as? Map<*, *>
                    ?: error("${fontProvenanceFile.path} has no font file inventory")
                val expectedFontHashes = fileTree("src/main/res/font") { include("jetbrainsmono_*.ttf") }
                    .files
                    .associate { it.name to sha256(it) }
                check(fontHashes == expectedFontHashes) {
                    "${fontProvenanceFile.path} does not authenticate the exact bundled font files"
                }

                check(releaseHostLicenseFiles.all { it.length() >= 128 }) {
                    "VM host license texts must be complete, non-empty files"
                }
                val guestNotice = releaseGuestComplianceFiles.first { it.name == "NOTICE" }
                val guestNoticeText = guestNotice.readText()
                val packagedHostLock = releaseHostMetadataFiles.first { it.name == "SOURCES.lock" }
                check(packagedHostLock.readBytes().contentEquals(canonicalHostSourceLock.readBytes())) {
                    "The packaged VM host source lock differs from native-runtime/sources.lock"
                }
                val hostLock = parseSourceLock(canonicalHostSourceLock)
                val hostRuntimeNames = setOf("qemu", "glib", "libiconv", "pcre2", "libffi", "proxy-libintl", "dtc")
                val hostRuntimeLock = hostLock.filterKeys(hostRuntimeNames::contains)
                check(hostRuntimeLock.keys == hostRuntimeNames) { "native-runtime/sources.lock is incomplete" }
                validateCycloneDxLock(
                    releaseHostMetadataFiles.first { it.name == "SBOM.cdx.json" },
                    hostRuntimeLock,
                    requireExactInventory = false,
                )

                val packagedGuestSourceLock = releaseGuestComplianceFiles.first { it.name == "SOURCES.lock" }
                val packagedGuestPackageLock = releaseGuestComplianceFiles.first { it.name == "PACKAGES.lock" }
                check(packagedGuestSourceLock.readBytes().contentEquals(canonicalGuestSourceLock.readBytes())) {
                    "The packaged guest source lock differs from guest-runtime/sources.lock"
                }
                check(packagedGuestPackageLock.readBytes().contentEquals(canonicalGuestPackageLock.readBytes())) {
                    "The packaged guest package lock differs from guest-runtime/packages.lock"
                }
                val guestSourceLock = parseSourceLock(canonicalGuestSourceLock)
                val guestPackageLock = parseSourceLock(canonicalGuestPackageLock)
                check("linux" in guestPackageLock && "busybox" in guestPackageLock && guestPackageLock.isNotEmpty()) {
                    "guest-runtime/packages.lock must cover the kernel and complete guest package inventory"
                }
                val guestSourceDigests = guestSourceLock.values.map { it.digest }.toSet()
                check(guestPackageLock.values.all { it.digest in guestSourceDigests }) {
                    "Every locked guest package must bind to an authenticated source input"
                }
                check(guestPackageLock.keys.all(guestNoticeText.lowercase()::contains)) {
                    "Guest NOTICE does not cover every locked guest package"
                }
                validateCycloneDxLock(
                    releaseGuestComplianceFiles.first { it.name == "SBOM.cdx.json" },
                    guestPackageLock,
                    requireExactInventory = true,
                )

                if (missingSourceDirectories.isEmpty()) {
                    validateSourceManifest(
                        releaseHostMetadataFiles.first { it.name == "SOURCE-MANIFEST.sha256" },
                        releaseHostSourceDirectory,
                    )
                    validateLockedSourceArchives(hostLock, releaseHostSourceDirectory)
                    validatePublishedBuildInputs(releaseHostSourceDirectory)
                    validateSourceManifest(
                        releaseGuestComplianceFiles.first { it.name == "SOURCE-MANIFEST.sha256" },
                        releaseGuestSourceDirectory,
                    )
                    validateLockedSourceArchives(guestSourceLock, releaseGuestSourceDirectory)
                    validatePublishedGuestBuildInputs(releaseGuestSourceDirectory, canonicalGuestBuildFiles)
                }
            }
            addAll(remainingReleaseImplementationBlockers)
            addAll(remainingReleaseComplianceBlockers)
        }
        check(blockers.isEmpty()) {
            "Google Play release blocked:\n- ${blockers.joinToString("\n- ")}"
        }
    }
}

val auditPlayCandidateNativeGraph by tasks.registering(Exec::class) {
    group = "verification"
    description = "Audits every native ELF in the generated release Android App Bundle."
    dependsOn("bundleRelease")
    inputs.file(releaseBundle)
    environment(
        "ANDROID_NDK_HOME",
        androidComponentsExtension.sdkComponents.ndkDirectory.get().asFile.absolutePath,
    )
    doFirst {
        commandLine(
            rootProject.file("native-runtime/audit-apk-native-libs.sh").absolutePath,
            releaseBundle.get().asFile.absolutePath,
        )
    }
}

val verifyPlaySubmission by tasks.registering {
    group = "verification"
    description = "Builds the candidate bundle, then enforces post-artifact evidence before Play submission."
    dependsOn(auditPlayCandidateNativeGraph, verifyPlaySubmissionEvidence)
    doLast {
        check(remainingPlaySubmissionBlockers.isEmpty()) {
            "Google Play submission blocked after candidate build:\n- " +
                remainingPlaySubmissionBlockers.joinToString("\n- ")
        }
    }
}

tasks.configureEach {
    if (name == "mergeReleaseAssets") {
        dependsOn(stageReleaseHostRuntime, verifyReleaseRuntimeBoundary)
    }
    if (name == "mergeReleaseNativeLibs") {
        dependsOn(verifyPlayRelease)
    }
    if (name.contains("Release") && name.contains("Lint", ignoreCase = true)) {
        dependsOn(stageReleaseHostRuntime)
    }
}

tasks.named("check").configure {
    dependsOn(
        testJetBrainsMonoReleaseEvidence,
        testMermaidReleaseEvidence,
        verifyPrototypeRuntimeBoundary,
        verifyReleaseRuntimeBoundary,
        verifyVmFdHygiene,
        verifyLegalInventory,
        verifyPlaySubmissionEvidenceSchema,
    )
}
