plugins {
    id("java")
    id("application")
    id("org.openjfx.javafxplugin") version "0.1.0"
}

import org.gradle.jvm.toolchain.JvmVendorSpec
import java.io.StringReader
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.Properties
import java.util.zip.ZipFile

group = "de.kortty"
// The korTTY application version — unrelated to llamaCppTag/llamaRuntimeRevision below,
// which version the bundled llama.cpp runtime independently.
version = "2.10.0"

// Resolved JDK major for this build. Native release packaging defaults to 25;
// the property remains available for explicit compatibility builds. Hoisted to
// top level so the jdk.jsobject wiring below can branch on it.
val korttyJavaVersion = (findProperty("kortty.javaVersion") as String?)?.toIntOrNull() ?: 25

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(korttyJavaVersion))
        // Explicitly avoid IBM_SEMERU which is not supported in Gradle 9.2.1
        vendor.set(JvmVendorSpec.ADOPTIUM)
    }
}

repositories {
    mavenLocal()
    mavenCentral()
    // JetBrains repository for pty4j and its dependencies
    maven {
        url = uri("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies")
    }
}

val osName = System.getProperty("os.name").lowercase()
val isWindows = osName.contains("windows")
val isMac = osName.contains("mac")
val isLinux = osName.contains("linux")

// llama.cpp is deliberately source-pinned. Runtime packages are built separately from the
// application installer and are installed below ~/.kortty/llm/runtime at run time. The pin itself
// lives in gradle/llama-cpp-pins.properties rather than here so llama-runtime.yml can tell a pin
// bump from an unrelated build change: editing anything else in this file runs a single-leg smoke
// instead of the ten-way runtime matrix. Keep the tag, full commit and GitHub source-archive digest
// in lockstep; downloadLlamaCppSource fails closed on any upstream/archive mismatch.
val llamaCppPinPath = "gradle/llama-cpp-pins.properties"
val llamaCppPinFile = layout.projectDirectory.file(llamaCppPinPath)
val llamaCppPinProperties = Properties().apply {
    val text = providers.fileContents(llamaCppPinFile).asText.orNull
        ?: throw GradleException("$llamaCppPinPath is missing; the llama.cpp pin cannot be resolved.")
    load(StringReader(text))
}
fun llamaCppPin(key: String): String =
    llamaCppPinProperties.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }
        ?: throw GradleException("$llamaCppPinPath has no \"$key\" entry.")
val llamaCppTag = llamaCppPin("tag")
val llamaCppCommit = llamaCppPin("commit")
val llamaCppSourceSha256 = llamaCppPin("sourceSha256")
// Every llama.cpp pin korTTY has shipped: tag -> (upstream commit, source archive SHA-256), stored
// as `pin.<tag> = <commit>:<sha256>` rows in the same file. verifyLlamaCppPin looks the ACTIVE tag
// up here and fails when the row is missing or disagrees with the three values above. A table
// lookup has no "unless the tag changed" escape hatch, unlike the
// `check(tag != "bNNNN" || commit.startsWith(...))` guard it replaces — that form silently
// switched itself off on the b10025 -> b10069 bump by making its left disjunct true, and would
// have done so again on the next one.
val llamaCppKnownPins: Map<String, Pair<String, String>> =
    llamaCppPinProperties.stringPropertyNames()
        .filter { it.startsWith("pin.") }
        .associate { name ->
            val row = llamaCppPinProperties.getProperty(name).trim().split(":")
            if (row.size != 2) {
                throw GradleException("$llamaCppPinPath: \"$name\" must read <commit>:<sha256>.")
            }
            name.removePrefix("pin.") to (row[0].trim() to row[1].trim())
        }
val llamaRuntimeRevision = "kortty2"
val llamaRuntimeApiContractVersion = 1
val llamaRuntimeId = "llama-$llamaCppTag-$llamaRuntimeRevision"
val llamaRuntimeIndexUrl =
    "https://github.com/chardonnay/kortty-llama-runtimes/releases/latest/download/runtime-index-v1.json"
val llamaRuntimeSignatureUrl =
    "https://github.com/chardonnay/kortty-llama-runtimes/releases/latest/download/runtime-index-v1.sig"

// The MLX channel is published from the same repository as a rolling `mlx-stable` release whose
// cumulative index is signed with the same Ed25519 release key as the llama.cpp channel.
val mlxRuntimeIndexUrl =
    "https://github.com/chardonnay/kortty-llama-runtimes/releases/download/mlx-stable/mlx-runtime-index-v1.json"
val mlxRuntimeSignatureUrl =
    "https://github.com/chardonnay/kortty-llama-runtimes/releases/download/mlx-stable/mlx-runtime-index-v1.sig"

// The Ed25519 public trust root is intentionally tracked so local and packaged builds verify the
// same signed runtime channel. It is public, auditable material; the private signing key must never
// enter this repository. CI may inject the public key redundantly, but an override must match the
// pinned file exactly so a compromised build environment cannot silently replace the trust root.
val llamaRuntimePublicKeyFile = layout.projectDirectory.file("config/trust/llama-runtime-ed25519-public.pem")
val pinnedLlamaRuntimePublicKey = providers.fileContents(llamaRuntimePublicKeyFile).asText.map { it.trim() }
val llamaRuntimePublicKeyOverride = providers.gradleProperty("kortty.llamaRuntimePublicKey")
    .orElse(providers.environmentVariable("KORTTY_LLAMA_RUNTIME_PUBLIC_KEY"))
val llamaRuntimePublicKey = llamaRuntimePublicKeyOverride.orElse(pinnedLlamaRuntimePublicKey)
val generatedLlamaRuntimeConfigDirectory = layout.buildDirectory.dir("generated/llama-runtime-config")
val generateLlamaRuntimeReleaseConfig = tasks.register("generateLlamaRuntimeReleaseConfig") {
    val outputFile = generatedLlamaRuntimeConfigDirectory.map {
        it.file("de/kortty/ai/runtimeupdate/llama-runtime-release.properties")
    }
    inputs.property("runtimeId", llamaRuntimeId)
    inputs.property("tag", llamaCppTag)
    inputs.property("commit", llamaCppCommit)
    inputs.property("apiContractVersion", llamaRuntimeApiContractVersion)
    inputs.property("indexUrl", llamaRuntimeIndexUrl)
    inputs.property("signatureUrl", llamaRuntimeSignatureUrl)
    inputs.property("mlxIndexUrl", mlxRuntimeIndexUrl)
    inputs.property("mlxSignatureUrl", mlxRuntimeSignatureUrl)
    inputs.property("publicKey", llamaRuntimePublicKey.orElse(""))
    inputs.file(llamaRuntimePublicKeyFile)
    outputs.file(outputFile)
    doLast {
        val configuredKey = llamaRuntimePublicKey.orNull?.trim().orEmpty()
        val pinnedKey = pinnedLlamaRuntimePublicKey.get().trim()
        fun normalizedPublicKey(value: String): String = value
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace(Regex("\\s+"), "")
        if (configuredKey.contains("PRIVATE KEY", ignoreCase = true)) {
            throw GradleException("kortty.llamaRuntimePublicKey must contain an Ed25519 public key, never a private key.")
        }
        if (configuredKey.isEmpty()) {
            throw GradleException("The pinned llama.cpp runtime Ed25519 public key is missing.")
        }
        if (llamaRuntimePublicKeyOverride.isPresent
            && normalizedPublicKey(configuredKey) != normalizedPublicKey(pinnedKey)) {
            throw GradleException(
                "The injected llama.cpp runtime public key does not match config/trust/llama-runtime-ed25519-public.pem."
            )
        }
        try {
            val decoded = Base64.getDecoder().decode(normalizedPublicKey(configuredKey))
            KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(decoded))
        } catch (error: Exception) {
            throw GradleException("kortty.llamaRuntimePublicKey is not a valid X.509 Ed25519 public key.", error)
        }
        fun propertyValue(value: String): String = value
            .replace("\\", "\\\\")
            .replace("\r", "")
            .replace("\n", "\\n")
        val content = buildString {
            appendLine("baseline.runtimeId=${propertyValue(llamaRuntimeId)}")
            appendLine("baseline.tag=${propertyValue(llamaCppTag)}")
            appendLine("baseline.commit=${propertyValue(llamaCppCommit)}")
            appendLine("baseline.apiContractVersion=$llamaRuntimeApiContractVersion")
            appendLine("stable.indexUrl=${propertyValue(llamaRuntimeIndexUrl)}")
            appendLine("stable.signatureUrl=${propertyValue(llamaRuntimeSignatureUrl)}")
            appendLine("mlx.stable.index.uri=${propertyValue(mlxRuntimeIndexUrl)}")
            appendLine("mlx.stable.signature.uri=${propertyValue(mlxRuntimeSignatureUrl)}")
            appendLine("trust.ed25519PublicKey=${propertyValue(configuredKey)}")
        }
        val file = outputFile.get().asFile.toPath()
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
    }
}

// Model recommendations and prompt-family mappings use a separate signed release channel and
// trust root from the native runtime index. Local/dev builds without this public key stay fully
// functional on the built-in bootstrap catalog, but never construct the remote catalog client.
val aiCatalogUrl =
    "https://github.com/chardonnay/kortty-ai-catalog/releases/latest/download/model-prompt-catalog-v1.json"
val aiCatalogSignatureUrl =
    "https://github.com/chardonnay/kortty-ai-catalog/releases/latest/download/model-prompt-catalog-v1.sig"
val aiCatalogPublicKey = providers.gradleProperty("kortty.aiCatalogPublicKey")
    .orElse(providers.environmentVariable("KORTTY_AI_CATALOG_PUBLIC_KEY"))
val generatedAiCatalogConfigDirectory = layout.buildDirectory.dir("generated/ai-catalog-config")
val generateAiCatalogReleaseConfig = tasks.register("generateAiCatalogReleaseConfig") {
    val outputFile = generatedAiCatalogConfigDirectory.map {
        it.file("de/kortty/ai/catalog/ai-catalog-release.properties")
    }
    inputs.property("catalogUrl", aiCatalogUrl)
    inputs.property("signatureUrl", aiCatalogSignatureUrl)
    inputs.property("publicKey", aiCatalogPublicKey.orElse(""))
    outputs.file(outputFile)
    doLast {
        val configuredKey = aiCatalogPublicKey.orNull?.trim().orEmpty()
        if (configuredKey.contains("PRIVATE KEY", ignoreCase = true)) {
            throw GradleException("kortty.aiCatalogPublicKey must contain an Ed25519 public key, never a private key.")
        }
        if (configuredKey.isNotEmpty()) {
            try {
                val encoded = configuredKey
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replace(Regex("\\s+"), "")
                val decoded = Base64.getDecoder().decode(encoded)
                KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(decoded))
            } catch (error: Exception) {
                throw GradleException("kortty.aiCatalogPublicKey is not a valid X.509 Ed25519 public key.", error)
            }
        }
        fun propertyValue(value: String): String = value
            .replace("\\", "\\\\")
            .replace("\r", "")
            .replace("\n", "\\n")
        val content = buildString {
            appendLine("stable.catalogUrl=${propertyValue(aiCatalogUrl)}")
            appendLine("stable.signatureUrl=${propertyValue(aiCatalogSignatureUrl)}")
            if (configuredKey.isNotEmpty()) {
                appendLine("trust.ed25519PublicKey=${propertyValue(configuredKey)}")
            }
        }
        val file = outputFile.get().asFile.toPath()
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
    }
}

sourceSets.named("main") {
    resources.srcDir(generatedLlamaRuntimeConfigDirectory)
    resources.srcDir(generatedAiCatalogConfigDirectory)
}

// Packaging must use the same JDK generation as compilation. Calling the bare
// `jpackage` from PATH is not reproducible and breaks as soon as a newer system
// JDK removes a module that the selected application toolchain still provides
// (for example jdk.jsobject in JDK 26).
val packagingJavaLauncher = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(korttyJavaVersion))
    vendor.set(JvmVendorSpec.ADOPTIUM)
}
val jpackageExecutable = packagingJavaLauncher.map { launcher ->
    launcher.metadata.installationPath
        .file(if (isWindows) "bin/jpackage.exe" else "bin/jpackage")
        .asFile.absolutePath
}

// Pinned to bare "21" (GA 21.0.0). Do NOT bump without solving the macOS signing trap below:
// JavaFX ships its native dylibs INSIDE the jars. Gluon's adhoc/linker signatures on patches >= 21.0.1
// are rejected by Apple's notary, so they'd need re-signing with our Developer ID — BUT re-signing
// libjfxwebkit.dylib with `codesign --options runtime` sets the hardened-runtime flag, which kills
// JavaScriptCore's JIT at runtime (the WebView boots, then the app is SIGKILLed the instant JS runs,
// with NO crash report). 21.0.0's Gluon signatures notarize as-is, so the GA stays un-re-signed and
// WebKit keeps working. A future bump must re-sign the javafx dylibs WITHOUT `--options runtime`.
val javaFxVersion = "21"
val javaFxJsObjectVersion = "25.0.2"
val javaFxPlatform = when {
    isWindows -> "win"
    isMac && System.getProperty("os.arch", "").lowercase() in setOf("aarch64", "arm64") -> "mac-aarch64"
    isMac -> "mac"
    isLinux && System.getProperty("os.arch", "").lowercase() in setOf("aarch64", "arm64") -> "linux-aarch64"
    isLinux -> "linux"
    else -> throw GradleException("Unsupported JavaFX platform: ${System.getProperty("os.name")} ${System.getProperty("os.arch")}")
}
val javaFxJsObject = configurations.create("javaFxJsObject")

javafx {
    version = javaFxVersion
    modules = listOf("javafx.controls", "javafx.graphics", "javafx.media", "javafx.swing", "javafx.web")
}

val motherTerminalEffectPluginJarName = "kortty-terminal-effect-mother.jar"
val motherPluginSourceSet = sourceSets.create("motherPlugin") {
    java.setSrcDirs(listOf("src/motherPlugin/java"))
    resources.setSrcDirs(listOf("src/motherPlugin/resources"))
    compileClasspath += sourceSets.main.get().output.classesDirs + configurations.compileClasspath.get()
    runtimeClasspath += output + compileClasspath
}

tasks.named<JavaCompile>(motherPluginSourceSet.compileJavaTaskName) {
    dependsOn(tasks.named("compileJava"))
}

sourceSets.named("test") {
    compileClasspath += motherPluginSourceSet.output.classesDirs
    runtimeClasspath += motherPluginSourceSet.output.classesDirs
}

val motherTerminalEffectPluginJar = tasks.register<Jar>("motherTerminalEffectPluginJar") {
    group = "build"
    description = "Builds the exportable MOTHER terminal effect plugin JAR."
    dependsOn(tasks.named(motherPluginSourceSet.classesTaskName))
    archiveFileName.set(motherTerminalEffectPluginJarName)
    destinationDirectory.set(layout.buildDirectory.dir("terminal-effect-plugins"))
    from(motherPluginSourceSet.output)
}

val effectPackPluginJarName = "kortty-terminal-effect-pack.jar"
val effectPackPluginSourceSet = sourceSets.create("effectPackPlugin") {
    java.setSrcDirs(listOf("src/effectPackPlugin/java"))
    resources.setSrcDirs(listOf("src/effectPackPlugin/resources"))
    compileClasspath += sourceSets.main.get().output.classesDirs + configurations.compileClasspath.get()
    runtimeClasspath += output + compileClasspath
}

tasks.named<JavaCompile>(effectPackPluginSourceSet.compileJavaTaskName) {
    dependsOn(tasks.named("compileJava"))
}

sourceSets.named("test") {
    // classesDirs only: adding the pack resources would double-register the plugins
    // via ServiceLoader on the application classloader in tests.
    compileClasspath += effectPackPluginSourceSet.output.classesDirs
    runtimeClasspath += effectPackPluginSourceSet.output.classesDirs
}

val effectPackPluginJar = tasks.register<Jar>("effectPackPluginJar") {
    group = "build"
    description = "Builds the exportable terminal effect pack plugin JAR (10 built-in effects)."
    dependsOn(tasks.named(effectPackPluginSourceSet.classesTaskName))
    archiveFileName.set(effectPackPluginJarName)
    destinationDirectory.set(layout.buildDirectory.dir("terminal-effect-plugins"))
    from(effectPackPluginSourceSet.output)
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(generateLlamaRuntimeReleaseConfig)
    dependsOn(generateAiCatalogReleaseConfig)
    dependsOn(motherTerminalEffectPluginJar)
    from(motherTerminalEffectPluginJar.flatMap { it.archiveFile }) {
        into("bundled-plugins/terminal-effects")
    }
    dependsOn(effectPackPluginJar)
    from(effectPackPluginJar.flatMap { it.archiveFile }) {
        into("bundled-plugins/terminal-effects")
    }
}

dependencies {
    // SSH
    implementation("org.apache.sshd:sshd-core:2.19.0")
    implementation("org.apache.sshd:sshd-common:2.19.0")
    implementation("org.apache.sshd:sshd-sftp:2.19.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.85.2")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.85.2")
    
    // ED25519 (EdDSA) key support for SSH
    implementation("net.i2p.crypto:eddsa:0.3.0")
    
    // SithTermFX - Terminal emulator for JavaFX (built from source by installSithtermfxLocal)
    implementation("com.sithtermfx:sithtermfx-core:1.2.1") {
        // SithTermFX 1.2.1 accidentally publishes its JUnit API as a runtime dependency.
        // korTTY supplies its own test dependencies and must not ship test frameworks.
        exclude(group = "org.junit.jupiter", module = "junit-jupiter-api")
    }
    implementation("com.sithtermfx:sithtermfx-ui:1.2.1") {
        exclude(group = "org.junit.jupiter", module = "junit-jupiter-api")
    }
    
    // Lanterna - Text-based terminal emulator with better zoom support
    implementation("com.googlecode.lanterna:lanterna:3.1.5")
    
    // XML Binding (JAXB)
    implementation("jakarta.xml.bind:jakarta.xml.bind-api:4.0.5")
    runtimeOnly("org.glassfish.jaxb:jaxb-runtime:4.0.9")
    
    // ZIP encryption
    implementation("net.lingala.zip4j:zip4j:2.11.6")
    
    // Archive support (TAR.BZ2, 7z)
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("org.tukaani:xz:1.12")
    
    // jfiglet - ASCII art banners (FIGfonts)
    implementation("com.github.lalyos:jfiglet:0.0.9")
    
    // Password strength (zxcvbn – offline, no network)
    implementation("com.nulab-inc:zxcvbn:1.9.0")
    
    // TOML parsing for the enterprise admin policy file (kortty-policy.toml)
    implementation("org.tomlj:tomlj:1.1.1")

    // JSON parsing for translation API responses
    implementation("com.google.code.gson:gson:2.14.0")
    implementation("com.knuddels:jtokkit:1.1.0")
    implementation("org.apache.pdfbox:pdfbox:3.0.8")
    implementation("com.google.googlejavaformat:google-java-format:1.36.1")

    // The JDK's built-in jdk.jsobject module is deprecated for removal on JDK 25,
    // so on JDK 25+ we supply it externally via the JavaFX artifact. On JDK 21
    // the module is still built in AND that javac
    // cannot read the 25.x module-info (Java 23 bytecode, class version 67.0) —
    // so we must NOT put the external artifact on the compile path there.
    if (korttyJavaVersion >= 24) {
        val javaFxJsObjectDependency = "org.openjfx:jdk-jsobject:$javaFxJsObjectVersion:$javaFxPlatform"
        compileOnly(javaFxJsObjectDependency)
        javaFxJsObject(javaFxJsObjectDependency)
    }

    // PTY support for native Mosh client.
    // PINNED to 0.12.25 — the last release that ships ONLY libpty.dylib. Every
    // release from 0.12.26 onward (verified: 0.12.35) AND all 0.13.x additionally
    // bundle resources/com/pty4j/native/darwin/pty4j-unix-spawn-helper, an
    // UNSIGNED native macOS binary. jpackage embeds the jar verbatim and that
    // helper is never code-signed, so Apple notarization fails with "Archive
    // contains critical validation errors" in build-release.yml. 0.12.25 notarizes
    // cleanly. Do NOT bump until pty4j ships a notarization-friendly helper, or
    // until the macOS signing step is taught to sign jar-internal natives.
    implementation("org.jetbrains.pty4j:pty4j:0.12.25")

    // Native desktop power-management integration. pty4j already brings these transitively, but
    // korTTY uses their APIs directly, so keep the compile/runtime contract explicit and pinned.
    implementation("net.java.dev.jna:jna:5.19.1")
    implementation("net.java.dev.jna:jna-platform:5.19.1")

    // Logging
    implementation("org.slf4j:slf4j-api:2.0.18")
    implementation("ch.qos.logback:logback-classic:1.6.1")
    
    // Testing
    testImplementation("com.google.truth:truth:1.4.5")
    testImplementation("org.testng:testng:7.12.0")
}

val googleJavaFormatJvmArgs = listOf(
    "--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
    "--add-exports=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED",
    "--add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED",
    "--add-exports=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED",
    "--add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
    "--add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED"
)

application {
    mainClass.set("de.kortty.KorTTYApplication")
    applicationDefaultJvmArgs = googleJavaFormatJvmArgs
}

tasks.named<JavaExec>("run") {
    dependsOn("copyBundledFormatters")
    systemProperty("kortty.formatters.dir", layout.buildDirectory.dir("bundled-formatters").get().asFile.absolutePath)
    // Dev-only enterprise-policy testing: ./gradlew run -Pkortty.policyFile=/path/policy.toml
    // (the override is ignored in packaged builds — see PolicyLocator).
    (findProperty("kortty.policyFile") as String?)?.let {
        systemProperty("kortty.policy.file", it)
    }
}

// ==================== SithTermFX from source (no GitHub token required) ====================

val sithtermfxVersion = "1.2.1"
val sithtermfxDir = layout.projectDirectory.dir("vendor/sithtermfx")
// Applied in order. Every patch ships its own marker resource so each one stays independently
// forward/reverse-checkable against the vendor tree regardless of which patches are present.
val sithtermfxPatchFiles = listOf(
    layout.projectDirectory.file("patches/sithtermfx/1.2.1-terminal-panel-bottom-row.patch"),
    layout.projectDirectory.file("patches/sithtermfx/1.2.1-terminal-panel-meta-shortcut-key-typed.patch"),
)
// Jar entry -> line that entry must contain for the installed artifact to count as patched.
val sithtermfxPatchMarkers = listOf(
    "META-INF/kortty-patches.properties" to "terminal-panel-bottom-row-hyperlink-boundary=1",
    "META-INF/kortty-patch-meta-shortcut-key-typed.properties" to "terminal-panel-meta-shortcut-key-typed=1",
)

tasks.register("cloneSithtermfx") {
    group = "build"
    description = "Clone SithTermFX source at v$sithtermfxVersion into vendor/sithtermfx (no token required)."
    val dir = sithtermfxDir.asFile
    doLast {
        val needClone = when {
            !dir.isDirectory -> true
            !dir.resolve("pom.xml").isFile -> true
            else -> {
                val check = ProcessBuilder("git", "describe", "--tags", "--exact-match")
                    .directory(dir)
                    .redirectError(ProcessBuilder.Redirect.PIPE)
                    .redirectOutput(ProcessBuilder.Redirect.PIPE)
                    .start()
                check.waitFor()
                check.inputStream.bufferedReader().readText().trim() != "v$sithtermfxVersion"
            }
        }
        if (needClone) {
            dir.parentFile.mkdirs()
            if (dir.exists()) project.delete(dir)
            val proc = ProcessBuilder(
                "git", "clone", "--depth", "1", "--branch", "v$sithtermfxVersion",
                "https://github.com/chardonnay/SithTermFX.git", dir.absolutePath
            ).directory(project.rootDir).inheritIO().start()
            if (proc.waitFor() != 0) throw GradleException("git clone SithTermFX failed")
        }
    }
}

val applySithtermfxPatches = tasks.register("applySithtermfxPatches") {
    group = "build"
    description = "Apply korTTY's reviewed patches to the pinned SithTermFX source tree."
    dependsOn("cloneSithtermfx")
    inputs.files(sithtermfxPatchFiles)
    // Always validate the ignored vendor tree. A manually reverted source file must not be
    // mistaken for a successfully patched clone merely because a previous output marker exists.
    outputs.upToDateWhen { false }
    doLast {
        val vendorDir = sithtermfxDir.asFile

        fun gitApplyCheck(patch: File, reverse: Boolean): Boolean {
            val command = mutableListOf("git", "apply", "--unidiff-zero")
            if (reverse) command.add("--reverse")
            command.add("--check")
            command.add(patch.absolutePath)
            return ProcessBuilder(command)
                .directory(vendorDir)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
                .waitFor() == 0
        }

        for (patchFile in sithtermfxPatchFiles) {
            val patch = patchFile.asFile
            if (gitApplyCheck(patch, reverse = false)) {
                val process = ProcessBuilder("git", "apply", "--unidiff-zero", patch.absolutePath)
                    .directory(vendorDir)
                    .inheritIO()
                    .start()
                if (process.waitFor() != 0) {
                    throw GradleException("Applying pinned SithTermFX patch ${patch.name} failed.")
                }
            } else if (!gitApplyCheck(patch, reverse = true)) {
                throw GradleException(
                    "Pinned SithTermFX patch ${patch.name} neither applies cleanly nor matches the " +
                        "source tree. Verify tag v$sithtermfxVersion before building."
                )
            }
        }
    }
}

val mavenLocalSithtermfxCore = File(System.getProperty("user.home"), ".m2/repository/com/sithtermfx/sithtermfx-core/$sithtermfxVersion/sithtermfx-core-$sithtermfxVersion.jar")
val mavenLocalSithtermfxUi = File(System.getProperty("user.home"), ".m2/repository/com/sithtermfx/sithtermfx-ui/$sithtermfxVersion/sithtermfx-ui-$sithtermfxVersion.jar")

fun installedSithtermfxHasRequiredPatches(): Boolean {
    if (!mavenLocalSithtermfxCore.isFile || !mavenLocalSithtermfxUi.isFile) return false
    return try {
        ZipFile(mavenLocalSithtermfxUi).use { archive ->
            sithtermfxPatchMarkers.all { (entryName, requiredLine) ->
                val marker = archive.getEntry(entryName) ?: return false
                archive.getInputStream(marker).bufferedReader().use { reader ->
                    reader.readText().lineSequence().any { it.trim() == requiredLine }
                }
            }
        }
    } catch (_: Exception) {
        false
    }
}

tasks.register<Exec>("installSithtermfxLocal") {
    group = "build"
    description = "Build SithTermFX from source and install to local Maven repo (requires Maven)."
    dependsOn(applySithtermfxPatches)
    workingDir(sithtermfxDir)
    // Use SITHTERMFX_JDK_HOME or -Psithtermfx.jdkHome for Maven (CI may build SithTermFX in workflow instead)
    val jdkHome = project.findProperty("sithtermfx.jdkHome")?.toString()?.takeIf { it.isNotBlank() }
        ?: System.getenv("SITHTERMFX_JDK_HOME")
        ?: System.getenv("JAVA_HOME")
    if (jdkHome != null) {
        environment("JAVA_HOME", jdkHome)
    }
    // SithTermFX's inherited compiler-plugin is too old for clean JDK 21/25
    // workers. Add the same pinned release-17 plugin used by release CI before
    // invoking Maven; the source/target flags remain a compatibility fallback.
    doFirst {
        val pom = sithtermfxDir.asFile.resolve("pom.xml")
        if (pom.isFile) {
            val text = pom.readText()
            if (!text.contains("<artifactId>maven-compiler-plugin</artifactId>")) {
                val buildBlock = """    <build>
                  <plugins>
                      <plugin>
                          <groupId>org.apache.maven.plugins</groupId>
                          <artifactId>maven-compiler-plugin</artifactId>
                          <version>3.13.0</version>
                          <configuration>
                              <release>17</release>
                          </configuration>
                      </plugin>
                  </plugins>
              </build>

"""
                pom.writeText(text.replaceFirst("    <profiles>\\n".toRegex(), buildBlock + "    <profiles>\n"))
            }
        }
    }
    val mavenArguments = listOf(
        "-q",
        "-DskipTests",
        "-Dmaven.compiler.source=17",
        "-Dmaven.compiler.target=17",
        "install"
    )
    if (isWindows) {
        commandLine(listOf("cmd", "/c", "mvn.cmd") + mavenArguments)
    } else {
        commandLine(listOf("mvn") + mavenArguments)
    }
    onlyIf {
        sithtermfxDir.asFile.resolve("pom.xml").isFile && !installedSithtermfxHasRequiredPatches()
    }
}

tasks.named("compileJava") {
    dependsOn("installSithtermfxLocal")
}

tasks.withType<JavaCompile>().configureEach {
    // Only upgrade the module path when the external jdk.jsobject artifact is
    // present (JDK 25+); on JDK 21 the config is empty and the JDK module is used.
    if (!javaFxJsObject.isEmpty) {
        options.compilerArgs.addAll(listOf("--upgrade-module-path", javaFxJsObject.asPath))
    }
    // NOTE: MacGlassQuitHook uses the internal com.sun.glass.ui.Application. No --add-exports is
    // needed at COMPILE time because the JavaFX artifacts are on the compile CLASS PATH here (the
    // unnamed module can read every package). The dev-run task and jpackage DO add the export,
    // because they put JavaFX on the module path where com.sun.glass.ui is only a qualified export.
}

// ==================== jpackage Konfiguration ====================

val jpackageDir = layout.buildDirectory.dir("jpackage")
val jpackageInput = layout.buildDirectory.dir("jpackage-input")

// ==================== Gebuendelte Code-Formatter ====================

val formatterDownloadDir = layout.buildDirectory.dir("formatter-downloads")
val bundledFormatterDir = layout.buildDirectory.dir("bundled-formatters")
val bundledMosh4jDir = layout.buildDirectory.dir("bundled-mosh4j")
val slimRuntimeJarDir = layout.buildDirectory.dir("slim-runtime-jars")
val monacoBuildNodeDir = layout.buildDirectory.dir("monaco-node")
val formatterNodeVersion = "24.18.1"
val formatterShfmtVersion = "3.13.1"
val formatterPrettierVersion = "3.6.2"
val formatterPrettierSha256 = "bc81ab83674f175a8601b7d013786f48ec2507dd4a5fcf3415831ff13a875bdf"
val formatterSqlFormatterVersion = "15.7.3"
val formatterSqlFormatterSha256 = "5ec54da8958d4ad9f6c948a8032ce55a2444361a9a9223766f8b4e75d2b29819"
val formatterPerlTidyVersion = "20260204"
val monacoEditorVersion = "0.56.0"
val monacoEditorSha256 = "b74bc4437205c194b779b0f21e5e7fcd3b4e9acbf3f7c8732a545d2059fb7412"
val monacoEsbuildVersion = "0.28.0"

fun formatterArch(): String = when (System.getProperty("os.arch", "").lowercase()) {
    "aarch64", "arm64" -> "arm64"
    "x86_64", "amd64" -> "x64"
    else -> System.getProperty("os.arch", "").lowercase()
}

fun shfmtAsset(): Pair<String, String>? {
    val arch = formatterArch()
    return when {
        isMac && arch == "x64" -> "shfmt_v${formatterShfmtVersion}_darwin_amd64" to "6feedafc72915794163114f512348e2437d080d0047ef8b8fa2ec63b575f12af"
        isMac && arch == "arm64" -> "shfmt_v${formatterShfmtVersion}_darwin_arm64" to "9680526be4a66ea1ffe988ed08af58e1400fe1e4f4aef5bd88b20bb9b3da33f8"
        isLinux && arch == "x64" -> "shfmt_v${formatterShfmtVersion}_linux_amd64" to "fb096c5d1ac6beabbdbaa2874d025badb03ee07929f0c9ff67563ce8c75398b1"
        isLinux && arch == "arm64" -> "shfmt_v${formatterShfmtVersion}_linux_arm64" to "32d92acaa5cd8abb29fc49dac123dc412442d5713967819d8af2c29f1b3857c7"
        isWindows && arch == "x64" -> "shfmt_v${formatterShfmtVersion}_windows_amd64.exe" to "60cd368533d0ad73fa86d93d5bbf95ef40587245ce684ed138c1b31557b5fe97"
        else -> null
    }
}

fun nodeArchiveName(): String? {
    val arch = formatterArch()
    return when {
        isMac && arch == "x64" -> "node-v${formatterNodeVersion}-darwin-x64.tar.gz"
        isMac && arch == "arm64" -> "node-v${formatterNodeVersion}-darwin-arm64.tar.gz"
        isLinux && arch == "x64" -> "node-v${formatterNodeVersion}-linux-x64.tar.gz"
        isLinux && arch == "arm64" -> "node-v${formatterNodeVersion}-linux-arm64.tar.gz"
        isWindows && arch == "x64" -> "node-v${formatterNodeVersion}-win-x64.zip"
        else -> null
    }
}

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

fun downloadPinned(url: String, target: File, expectedSha256: String) {
    target.parentFile.mkdirs()
    if (!target.isFile || sha256(target) != expectedSha256) {
        URI(url).toURL().openStream().use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
    }
    val actual = sha256(target)
    if (!actual.equals(expectedSha256, ignoreCase = true)) {
        throw GradleException("Checksum mismatch for ${target.name}: expected $expectedSha256, got $actual")
    }
}

fun download(url: String, target: File) {
    target.parentFile.mkdirs()
    if (!target.isFile) {
        URI(url).toURL().openStream().use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
    }
}

fun littleEndian16(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

fun littleEndian32(bytes: ByteArray, offset: Int): Int =
    littleEndian16(bytes, offset) or (littleEndian16(bytes, offset + 2) shl 16)

fun validateNativeArchitecture(nativeFile: File, targetArch: String) {
    val bytes = nativeFile.readBytes()
    val valid = when {
        isMac -> {
            bytes.size >= 8 && littleEndian32(bytes, 0) == 0xfeedfacf.toInt() &&
                littleEndian32(bytes, 4) == if (targetArch == "arm64") 0x0100000c else 0x01000007
        }
        isLinux -> {
            val expectedMachine = if (targetArch == "arm64") 183 else 62
            bytes.size >= 20 && bytes[0] == 0x7f.toByte() && bytes[1] == 'E'.code.toByte() &&
                bytes[2] == 'L'.code.toByte() && bytes[3] == 'F'.code.toByte() && bytes[5] == 1.toByte() &&
                littleEndian16(bytes, 18) == expectedMachine
        }
        isWindows -> {
            if (bytes.size < 64 || bytes[0] != 'M'.code.toByte() || bytes[1] != 'Z'.code.toByte()) {
                false
            } else {
                val peOffset = littleEndian32(bytes, 0x3c)
                val expectedMachine = if (targetArch == "arm64") 0xaa64 else 0x8664
                peOffset >= 0 && peOffset + 6 <= bytes.size &&
                    bytes[peOffset] == 'P'.code.toByte() && bytes[peOffset + 1] == 'E'.code.toByte() &&
                    littleEndian16(bytes, peOffset + 4) == expectedMachine
            }
        }
        else -> false
    }
    if (!valid) {
        throw GradleException("Native binary has the wrong architecture for $osName/$targetArch: ${nativeFile.name}")
    }
}

fun stripFirstPathSegment(details: org.gradle.api.file.FileCopyDetails) {
    val segments = details.relativePath.segments
    if (segments.size > 1) {
        details.relativePath = org.gradle.api.file.RelativePath(true, *segments.drop(1).toTypedArray())
    }
}

val cleanBundledFormatterDir = tasks.register<Delete>("cleanBundledFormatterDir") {
    delete(bundledFormatterDir)
}

tasks.register("copyBundledShfmt") {
    group = "build"
    description = "Downloads pinned shfmt and copies it into the jpackage formatter directory."
    doLast {
        val asset = shfmtAsset()
        if (asset == null) {
            logger.warn("No bundled shfmt asset configured for ${System.getProperty("os.name")} ${System.getProperty("os.arch")}; skipping.")
            return@doLast
        }
        val (assetName, checksum) = asset
        val downloadFile = formatterDownloadDir.get().asFile.resolve(assetName)
        downloadPinned(
            "https://github.com/mvdan/sh/releases/download/v$formatterShfmtVersion/$assetName",
            downloadFile,
            checksum
        )
        val executableName = if (isWindows) "shfmt.exe" else "shfmt"
        val target = bundledFormatterDir.get().asFile.resolve("bin").resolve(executableName)
        target.parentFile.mkdirs()
        downloadFile.copyTo(target, overwrite = true)
        target.setExecutable(true, false)
    }
}

fun monacoNodeExecutable(): File {
    val nodeRoot = monacoBuildNodeDir.get().asFile
    return if (isWindows) nodeRoot.resolve("node.exe") else nodeRoot.resolve("bin/node")
}

fun monacoNpmCliScript(): File {
    // The Windows Node .zip ships npm at <root>/node_modules/npm, while the Unix
    // tarballs ship it at <root>/lib/node_modules/npm. Resolve per-OS, otherwise
    // node.exe is handed a non-existent script path on Windows and npm never runs.
    val root = monacoBuildNodeDir.get().asFile
    return if (isWindows) {
        root.resolve("node_modules/npm/bin/npm-cli.js")
    } else {
        root.resolve("lib/node_modules/npm/bin/npm-cli.js")
    }
}

fun runBundledNodeCommand(workingDirectory: File, vararg command: String) {
    val nodeBinDir = File(command.first()).parentFile.absolutePath
    val builder = ProcessBuilder(command.toList())
        .directory(workingDirectory)
        .redirectErrorStream(true) // merge stderr into stdout so npm errors are visible
    val environment = builder.environment()
    val pathVar = environment["PATH"]
    environment["PATH"] = if (pathVar.isNullOrBlank()) {
        nodeBinDir
    } else {
        "$nodeBinDir${File.pathSeparator}$pathVar"
    }
    // Capture+echo the output. Relying on inheritIO() produced NO visible output
    // for failures in CI, which made Windows npm failures undiagnosable.
    val process = builder.start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    val exitCode = process.waitFor()
    if (output.isNotBlank()) {
        println(output)
    }
    if (exitCode != 0) {
        throw GradleException(
            "Command failed with exit code $exitCode: ${command.joinToString(" ")}\n$output"
        )
    }
}

val monacoWorkspaceDir = layout.buildDirectory.dir("monaco-workspace")
val monacoGeneratedResourceDir = layout.buildDirectory.dir("generated-monaco-resources")
val mermaidGeneratedResourceDir = layout.buildDirectory.dir("generated-mermaid-resources")
val mathJaxGeneratedResourceDir = layout.buildDirectory.dir("generated-mathjax-resources")
val formatterWebGeneratedResourceDir = layout.buildDirectory.dir("generated-formatter-web-resources")

tasks.register("copyMonacoBuildNode") {
    group = "build"
    description = "Downloads pinned Node.js LTS for the Monaco build workspace without touching jpackage inputs."
    outputs.dir(monacoBuildNodeDir)
    doLast {
        val archiveName = nodeArchiveName()
        if (archiveName == null) {
            throw GradleException("No bundled Node.js archive configured for ${System.getProperty("os.name")} ${System.getProperty("os.arch")}")
        }
        val downloadBase = "https://nodejs.org/dist/v$formatterNodeVersion"
        val shasumsFile = formatterDownloadDir.get().asFile.resolve("node-v$formatterNodeVersion-SHASUMS256.txt")
        download("$downloadBase/SHASUMS256.txt", shasumsFile)
        val expected = shasumsFile.readLines()
            .firstOrNull { it.endsWith("  $archiveName") || it.endsWith(" *$archiveName") }
            ?.substringBefore(" ")
            ?: throw GradleException("No Node.js checksum found for $archiveName")
        val archive = formatterDownloadDir.get().asFile.resolve(archiveName)
        downloadPinned("$downloadBase/$archiveName", archive, expected)

        val unpackDir = layout.buildDirectory.get().asFile.resolve("monaco-node-unpack")
        delete(unpackDir)
        unpackDir.mkdirs()
        copy {
            from(if (archiveName.endsWith(".zip")) zipTree(archive) else tarTree(resources.gzip(archive)))
            into(unpackDir)
        }
        val unpackedRoot = unpackDir.listFiles()?.singleOrNull { it.isDirectory }
            ?: throw GradleException("Could not find unpacked Node.js root for $archiveName")
        val target = monacoBuildNodeDir.get().asFile
        delete(target)
        copy {
            from(unpackedRoot)
            into(target)
        }
        val nodeExecutable = monacoNodeExecutable()
        nodeExecutable.setExecutable(true, false)
    }
}

tasks.register("prepareMonacoWorkspace") {
    group = "build"
    description = "Creates a pinned Monaco/npm workspace under build/ without using a global Node.js installation."
    dependsOn("copyMonacoBuildNode")
    inputs.dir("src/monaco")
    inputs.property("monacoEditorVersion", monacoEditorVersion)
    inputs.property("monacoEditorSha256", monacoEditorSha256)
    inputs.property("monacoEsbuildVersion", monacoEsbuildVersion)
    outputs.dir(monacoWorkspaceDir)
    doLast {
        val workspace = monacoWorkspaceDir.get().asFile
        delete(workspace)
        workspace.mkdirs()
        copy {
            from("src/monaco")
            into(workspace.resolve("src"))
        }
        val cachedTarball = formatterDownloadDir.get().asFile.resolve("monaco-editor-$monacoEditorVersion.tgz")
        downloadPinned(
            "https://registry.npmjs.org/monaco-editor/-/monaco-editor-$monacoEditorVersion.tgz",
            cachedTarball,
            monacoEditorSha256
        )
        // Depend on the tarball via a RELATIVE file: spec resolved against the
        // workspace. Java's File.toURI() yields "file:/C:/..." on Windows, which
        // npm cannot parse as a path (it only worked on Unix because the path was
        // "/home/..."). Copy the SHA-verified tarball into the workspace and depend
        // on it by bare filename so npm resolves it natively on every OS.
        val localMonacoTarballName = "monaco-editor-$monacoEditorVersion.tgz"
        cachedTarball.copyTo(workspace.resolve(localMonacoTarballName), overwrite = true)
        workspace.resolve("package.json").writeText(
            """
            {
              "private": true,
              "type": "module",
              "scripts": {
                "build": "node src/build-monaco.mjs ${monacoGeneratedResourceDir.get().asFile.resolve("monaco").absolutePath.replace("\\", "\\\\")}"
              },
              "dependencies": {
                "esbuild": "${monacoEsbuildVersion}",
                "monaco-editor": "file:$localMonacoTarballName"
              }
            }
            """.trimIndent()
        )
        runBundledNodeCommand(
            workspace,
            monacoNodeExecutable().absolutePath,
            monacoNpmCliScript().absolutePath,
            "install",
            "--package-lock-only",
            "--ignore-scripts"
        )
        runBundledNodeCommand(
            workspace,
            monacoNodeExecutable().absolutePath,
            monacoNpmCliScript().absolutePath,
            "ci",
            "--ignore-scripts"
        )
        val monacoArchive = workspace.resolve("node_modules/monaco-editor/package.json")
        if (!monacoArchive.isFile) {
            throw GradleException("monaco-editor $monacoEditorVersion was not installed in the local build workspace")
        }
        if (!monacoArchive.readText().contains("\"version\": \"$monacoEditorVersion\"")) {
            throw GradleException("Installed monaco-editor package does not match $monacoEditorVersion")
        }
    }
}

tasks.register("bundleMonacoEditor") {
    group = "build"
    description = "Bundles Monaco Editor and its web workers as local JavaFX WebView resources."
    dependsOn("prepareMonacoWorkspace")
    inputs.dir("src/monaco")
    outputs.dir(monacoGeneratedResourceDir)
    doLast {
        val outputDir = monacoGeneratedResourceDir.get().asFile
        delete(outputDir)
        runBundledNodeCommand(
            monacoWorkspaceDir.get().asFile,
            monacoNodeExecutable().absolutePath,
            monacoNpmCliScript().absolutePath,
            "run",
            "build"
        )
    }
}

// ---- Node-free browser formatter assets -------------------------------------
// JavaFX WebView already ships a JavaScript engine for Monaco, the guide and chat rendering.
// Bundle only Prettier's browser API/plugins and sql-formatter's UMD build instead of a ~114 MiB
// Node runtime plus complete npm package trees.
tasks.register("prepareFormatterWebResources") {
    group = "build"
    description = "Extracts the pinned Node-free Prettier and SQL formatter browser bundles."
    inputs.property("prettierVersion", formatterPrettierVersion)
    inputs.property("prettierSha256", formatterPrettierSha256)
    inputs.property("sqlFormatterVersion", formatterSqlFormatterVersion)
    inputs.property("sqlFormatterSha256", formatterSqlFormatterSha256)
    outputs.dir(formatterWebGeneratedResourceDir)
    doLast {
        val outputRoot = formatterWebGeneratedResourceDir.get().asFile
        val outputDir = outputRoot.resolve("formatters-web")
        delete(outputRoot)
        outputDir.mkdirs()

        val prettierArchive = formatterDownloadDir.get().asFile.resolve("prettier-$formatterPrettierVersion.tgz")
        downloadPinned(
            "https://registry.npmjs.org/prettier/-/prettier-$formatterPrettierVersion.tgz",
            prettierArchive,
            formatterPrettierSha256
        )
        val prettierFiles = mapOf(
            "package/standalone.js" to "prettier-standalone.js",
            "package/plugins/babel.js" to "prettier-plugin-babel.js",
            "package/plugins/estree.js" to "prettier-plugin-estree.js",
            "package/plugins/typescript.js" to "prettier-plugin-typescript.js",
            "package/plugins/html.js" to "prettier-plugin-html.js",
            "package/plugins/postcss.js" to "prettier-plugin-postcss.js"
        )
        copy {
            from(tarTree(resources.gzip(prettierArchive)))
            include(prettierFiles.keys)
            eachFile {
                val targetName = prettierFiles[path]
                    ?: throw GradleException("Unexpected Prettier browser resource: $path")
                relativePath = org.gradle.api.file.RelativePath(true, targetName)
            }
            includeEmptyDirs = false
            into(outputDir)
        }

        val sqlArchive = formatterDownloadDir.get().asFile.resolve("sql-formatter-$formatterSqlFormatterVersion.tgz")
        downloadPinned(
            "https://registry.npmjs.org/sql-formatter/-/sql-formatter-$formatterSqlFormatterVersion.tgz",
            sqlArchive,
            formatterSqlFormatterSha256
        )
        copy {
            from(tarTree(resources.gzip(sqlArchive)))
            include("package/dist/sql-formatter.min.cjs")
            eachFile { relativePath = org.gradle.api.file.RelativePath(true, "sql-formatter.js") }
            includeEmptyDirs = false
            into(outputDir)
        }

        val expectedFiles = prettierFiles.values + "sql-formatter.js"
        val missing = expectedFiles.filterNot { outputDir.resolve(it).isFile }
        if (missing.isNotEmpty()) {
            throw GradleException("Missing browser formatter resources: ${missing.joinToString()}")
        }
    }
}

tasks.named<ProcessResources>("processResources") {
    dependsOn("bundleMonacoEditor")
    from(monacoGeneratedResourceDir) {
        into("")
    }
    dependsOn("prepareMermaidResources")
    from(mermaidGeneratedResourceDir) {
        into("")
    }
    dependsOn("prepareMathJaxResources")
    from(mathJaxGeneratedResourceDir) {
        into("")
    }
    dependsOn("prepareFormatterWebResources")
    from(formatterWebGeneratedResourceDir) {
        into("")
    }
    // Jar slimming: sourcemaps and the Thai/Japanese search segmenters are dead weight in the
    // bundled guide (UI languages are en/de; lunr loads wordcut/tinyseg only for th/ja). The
    // .icns/.ico are packaging-only inputs that jpackage reads from the source tree, not the jar.
    exclude("guide/**/*.map")
    exclude("guide/**/assets/javascripts/lunr/wordcut.js")
    exclude("guide/**/assets/javascripts/lunr/tinyseg.js")
    exclude("icon/kortty_icon.icns")
    exclude("icon/kortty_icon.ico")
}

// ---- Mermaid diagram and AI-chat MathJax assets -------------------------------
// The complete Mermaid browser bundle is syntax-lowered at build time with the already pinned
// Monaco Node/esbuild workspace because JavaFX 21 WebKit cannot parse the upstream target. Node
// remains a build-only tool. Mermaid and MathJax stay separate runtime resources so each hidden
// WebView extracts only the library it needs.
val mermaidVersion = "11.16.0"
val mermaidSha256 = "ff48c94a0a0458b377a5187ad01407184d2a182e6476c2015b7068ff58355fae"
val chatRenderMathJaxVersion = "3.2.2"
val chatRenderMathJaxSha256 = "1b9c0a1c44df864e915690558e72adb9cc5203360daefd385084ced3b6c64c09"

tasks.register("prepareMermaidResources") {
    group = "build"
    description = "Builds the pinned Mermaid browser bundle for the JavaFX-compatible central renderer."
    dependsOn("prepareMonacoWorkspace")
    inputs.property("mermaidVersion", mermaidVersion)
    inputs.property("mermaidSha256", mermaidSha256)
    inputs.property("esbuildVersion", monacoEsbuildVersion)
    inputs.file("src/mermaid/build-mermaid.mjs")
    outputs.dir(mermaidGeneratedResourceDir)
    doLast {
        val outputDir = mermaidGeneratedResourceDir.get().asFile.resolve("mermaid")
        delete(mermaidGeneratedResourceDir)
        outputDir.mkdirs()

        val mermaidTarball = formatterDownloadDir.get().asFile.resolve("mermaid-$mermaidVersion.tgz")
        downloadPinned(
            "https://registry.npmjs.org/mermaid/-/mermaid-$mermaidVersion.tgz",
            mermaidTarball,
            mermaidSha256
        )
        copy {
            from(tarTree(resources.gzip(mermaidTarball)))
            include("package/dist/mermaid.min.js")
            eachFile { path = "mermaid.upstream.min.js" }
            includeEmptyDirs = false
            into(outputDir)
        }
        val upstreamBundle = outputDir.resolve("mermaid.upstream.min.js")
        if (!upstreamBundle.isFile) {
            throw GradleException("mermaid.min.js was not extracted from the Mermaid tarball")
        }
        val outputBundle = outputDir.resolve("mermaid.min.js")
        val esbuildModule = monacoWorkspaceDir.get().asFile.resolve("node_modules/esbuild/lib/main.js")
        runBundledNodeCommand(
            monacoWorkspaceDir.get().asFile,
            monacoNodeExecutable().absolutePath,
            file("src/mermaid/build-mermaid.mjs").absolutePath,
            upstreamBundle.absolutePath,
            outputBundle.absolutePath,
            esbuildModule.absolutePath
        )
        delete(upstreamBundle)
        if (!outputBundle.isFile || outputBundle.length() < 1_000_000L
            || !outputBundle.readText().contains("globalThis.mermaid=")) {
            throw GradleException("JavaFX-compatible Mermaid bundle was not generated correctly")
        }
    }
}

tasks.register("prepareMathJaxResources") {
    group = "build"
    description = "Extracts the pinned MathJax browser bundle for AI-chat math rendering."
    inputs.property("mathJaxVersion", chatRenderMathJaxVersion)
    inputs.property("mathJaxSha256", chatRenderMathJaxSha256)
    outputs.dir(mathJaxGeneratedResourceDir)
    doLast {
        val outputDir = mathJaxGeneratedResourceDir.get().asFile.resolve("chatrender")
        delete(mathJaxGeneratedResourceDir)
        outputDir.mkdirs()

        val mathJaxTarball = formatterDownloadDir.get().asFile.resolve("mathjax-$chatRenderMathJaxVersion.tgz")
        downloadPinned(
            "https://registry.npmjs.org/mathjax/-/mathjax-$chatRenderMathJaxVersion.tgz",
            mathJaxTarball,
            chatRenderMathJaxSha256
        )
        copy {
            from(tarTree(resources.gzip(mathJaxTarball)))
            include("package/es5/tex-svg.js")
            eachFile { path = name }
            includeEmptyDirs = false
            into(outputDir)
        }

        if (!outputDir.resolve("tex-svg.js").isFile) {
            throw GradleException("tex-svg.js was not extracted from the MathJax tarball")
        }
    }
}

// ---- Documentation guide site (MkDocs Material) -------------------------------
// Builds the bilingual, fully-offline guide into build/guide. The build output is
// COMMITTED into the repo under src/main/resources/guide/ (via stageGuideIntoResources)
// and bundled into the app under /guide/** like the bundled Monaco editor, so Help ->
// Anleitung (GuideViewer) always finds it on the classpath without the MkDocs toolchain.
// The same build/guide output is published to GitHub Pages by .github/workflows/docs-site.yml.

val docsVenvDir = layout.projectDirectory.dir(".venv-docs")
val guideSiteOutputDir = layout.buildDirectory.dir("guide")

/** Resolves the docs venv Python (Scripts/python.exe on Windows, bin/python elsewhere), or "python3" if the venv is absent. */
fun docsPythonExecutable(): String {
    val isWindows = System.getProperty("os.name", "").lowercase().contains("win")
    val venvPython = docsVenvDir.asFile.resolve(if (isWindows) "Scripts/python.exe" else "bin/python")
    return if (venvPython.exists()) venvPython.absolutePath else "python3"
}

/** Runs a docs-build command in [workingDirectory] with [extraEnv] applied, streaming its merged output; returns the exit code. */
fun runDocsCommand(workingDirectory: File, extraEnv: Map<String, String>, vararg command: String): Int {
    val builder = ProcessBuilder(command.toList())
        .directory(workingDirectory)
        .redirectErrorStream(true)
    builder.environment().putAll(extraEnv)
    val process = builder.start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    val exitCode = process.waitFor()
    if (output.isNotBlank()) println(output)
    return exitCode
}

tasks.register("setupDocsVenv") {
    group = "documentation"
    description = "Creates .venv-docs and installs the pinned MkDocs Material toolchain (needs network)."
    val requirements = layout.projectDirectory.file("app-docs/site/requirements.txt").asFile
    inputs.file(requirements)
    outputs.dir(docsVenvDir)
    doLast {
        val venv = docsVenvDir.asFile
        // Resolve the venv interpreter the same way docsPythonExecutable() does so the
        // bootstrap works on Windows (Scripts/python.exe) as well as Unix (bin/python).
        val isWindows = System.getProperty("os.name", "").lowercase().contains("win")
        val py = venv.resolve(if (isWindows) "Scripts/python.exe" else "bin/python")
        if (!py.exists()) {
            val bootstrapPython = if (isWindows) "py" else "python3"
            val rc = runDocsCommand(rootDir, emptyMap(), bootstrapPython, "-m", "venv", venv.absolutePath)
            if (rc != 0) throw GradleException("Could not create .venv-docs (python -m venv failed, exit $rc)")
        }
        var rc = runDocsCommand(rootDir, emptyMap(), py.absolutePath, "-m", "pip", "install", "--quiet", "--upgrade", "pip")
        if (rc != 0) throw GradleException("pip upgrade failed (exit $rc)")
        rc = runDocsCommand(rootDir, emptyMap(), py.absolutePath, "-m", "pip", "install", "--quiet", "-r", requirements.absolutePath)
        if (rc != 0) throw GradleException("pip install of the MkDocs toolchain failed (exit $rc)")
    }
}

tasks.register("buildDocsSite") {
    group = "documentation"
    description = "Builds the bilingual offline guide site into build/guide for bundling and Pages."
    inputs.dir("app-docs/site/docs")
    inputs.dir("app-docs/site/overrides")
    inputs.dir("app-docs/site/vendor")
    inputs.dir("app-docs/screenshots")
    inputs.file("app-docs/site/mkdocs.yml")
    inputs.file("app-docs/site/mkdocs.en.yml")
    inputs.file("app-docs/site/mkdocs.de.yml")
    inputs.dir("app-docs/diagrams")
    inputs.file("scripts/build-docs-site.py")
    inputs.property("version", project.version.toString())
    outputs.dir(guideSiteOutputDir)
    doLast {
        val outDir = guideSiteOutputDir.get().asFile
        delete(outDir)
        val builderScript = rootDir.resolve("scripts/build-docs-site.py")
        val rc = runDocsCommand(
            rootDir,
            mapOf("KORTTY_VERSION" to project.version.toString()),
            docsPythonExecutable(), builderScript.absolutePath
        )
        if (rc != 0) {
            logger.warn(
                "buildDocsSite: guide build failed or MkDocs unavailable (exit {}). Bundling a placeholder. " +
                    "Run ':setupDocsVenv' then ':buildDocsSite' to bundle the full guide.", rc
            )
            val placeholder = outDir.resolve("en/index.html")
            placeholder.parentFile.mkdirs()
            placeholder.writeText(
                "<!doctype html><html><head><meta charset=\"utf-8\"><title>korTTY Guide</title></head>" +
                    "<body style=\"background:#07111d;color:#e6f3ff;font-family:sans-serif;padding:2rem\">" +
                    "<h1>korTTY Guide</h1><p>The bundled guide was not built in this environment.</p>" +
                    "<p><a style=\"color:#38bdf8\" href=\"https://chardonnay.github.io/korTTY/\">Open the online guide</a></p>" +
                    "</body></html>"
            )
        }
    }
}

// The built guide site is COMMITTED under src/main/resources/guide/ and bundled into
// the app under /guide/** like any other resource, so a normal build (and the packaged
// app) no longer needs the MkDocs toolchain — the GuideViewer always finds a real guide.
// Refresh the committed copy after editing the docs with `./gradlew stageGuideIntoResources`
// (builds the site, then syncs it into the source tree) and commit the resulting diff.
// CI also keeps it in sync: .github/workflows/docs-autocommit.yml rebuilds the guide and
// commits it back when the Markdown sources change, so refreshing it by hand is optional.
val guideResourcesDir = layout.projectDirectory.dir("src/main/resources/guide")

tasks.register("stageGuideIntoResources") {
    group = "documentation"
    description = "Builds the guide and syncs it into src/main/resources/guide so the built docs are committed to the repo."
    dependsOn("buildDocsSite")
    inputs.dir(guideSiteOutputDir)
    outputs.dir(guideResourcesDir)
    doLast {
        val built = guideSiteOutputDir.get().asFile
        val enIndex = built.resolve("en/index.html")
        // Never overwrite the committed guide with the buildDocsSite placeholder (written
        // when MkDocs is unavailable): the real Material site contains the "md-header" markup.
        if (!enIndex.isFile || !enIndex.readText().contains("md-header")) {
            logger.warn(
                "stageGuideIntoResources: build/guide has no real MkDocs site (placeholder or empty); " +
                    "leaving the committed src/main/resources/guide untouched. " +
                    "Run ':setupDocsVenv' then ':buildDocsSite' to produce the full guide."
            )
            return@doLast
        }
        val dest = guideResourcesDir.asFile
        delete(dest)
        copy {
            from(built)
            into(dest)
            // The sitemap's <lastmod> is the build date, so committing it would make the
            // bundle non-reproducible (docs-autocommit's staleness check on main would fail
            // on any rebuild after midnight UTC). The in-app viewer never reads a sitemap;
            // GitHub Pages publishes its own freshly built copy including one.
            exclude("**/sitemap.xml", "**/sitemap.xml.gz")
        }
        logger.lifecycle("stageGuideIntoResources: synced build/guide -> src/main/resources/guide")
    }
}

tasks.register("copyBundledPerlTidy") {
    group = "build"
    description = "Downloads pinned Perl::Tidy and copies it into the jpackage formatter directory."
    doLast {
        val archive = formatterDownloadDir.get().asFile.resolve("Perl-Tidy-$formatterPerlTidyVersion.tar.gz")
        downloadPinned(
            "https://cpan.metacpan.org/authors/id/S/SH/SHANCOCK/Perl-Tidy-$formatterPerlTidyVersion.tar.gz",
            archive,
            "56a1fc2f1f813e49026a0f284b9209a6b2824620993e7598c85b01c444ff0f64"
        )
        val target = bundledFormatterDir.get().asFile.resolve("perltidy")
        delete(target)
        copy {
            from(tarTree(resources.gzip(archive))) {
                include("Perl-Tidy-$formatterPerlTidyVersion/bin/**")
                include("Perl-Tidy-$formatterPerlTidyVersion/lib/**")
                include("Perl-Tidy-$formatterPerlTidyVersion/COPYING")
                eachFile { stripFirstPathSegment(this) }
                includeEmptyDirs = false
            }
            into(target)
        }
        target.resolve("bin/perltidy").setExecutable(true, false)
    }
}

tasks.register("copyBundledFormatterManifest") {
    group = "build"
    description = "Writes the bundled formatter manifest next to jpackage formatter artifacts."
    doLast {
        val target = bundledFormatterDir.get().asFile.resolve("formatter-manifest.properties")
        target.parentFile.mkdirs()
        target.writeText(
            """
            google-java-format.version=1.36.1
            google-java-format.source=https://central.sonatype.com/artifact/com.google.googlejavaformat/google-java-format/1.36.1
            web-formatter.engine=javafx-web
            shfmt.version=$formatterShfmtVersion
            shfmt.source=https://github.com/mvdan/sh/releases/tag/v$formatterShfmtVersion
            prettier.version=$formatterPrettierVersion
            prettier.source=https://www.npmjs.com/package/prettier/v/$formatterPrettierVersion
            sql-formatter.version=$formatterSqlFormatterVersion
            sql-formatter.source=https://www.npmjs.com/package/sql-formatter/v/$formatterSqlFormatterVersion
            perltidy.version=$formatterPerlTidyVersion
            perltidy.source=https://metacpan.org/dist/Perl-Tidy/view/lib/Perl/Tidy.pod
            """.trimIndent() + "\n"
        )
    }
}

tasks.register("copyBundledFormatters") {
    group = "build"
    description = "Builds the small external formatter payload staged into native packages."
    dependsOn(
        "copyBundledShfmt",
        "copyBundledPerlTidy",
        "copyBundledFormatterManifest"
    )
}

listOf(
    "copyBundledShfmt",
    "copyBundledPerlTidy",
    "copyBundledFormatterManifest"
).forEach { formatterTaskName ->
    tasks.named(formatterTaskName) {
        dependsOn(cleanBundledFormatterDir)
    }
}

// mosh4j release to bundle.
// mosh4jVersion must match Mosh4jTtyConnector.MOSH4J_VERSION (artifact/file-name version, no "v").
// mosh4jReleaseTag must match Mosh4jTtyConnector.MOSH4J_RELEASE_TAG (GitHub tag; carries a leading "v" since 2.0.1).
val mosh4jVersion = "2.0.2"
val mosh4jReleaseTag = "v$mosh4jVersion"
val mosh4jReleaseUrl = "https://github.com/chardonnay/mosh4j/releases/download/$mosh4jReleaseTag"
val mosh4jModules = listOf("protocol", "crypto", "transport", "terminal", "core")
val mosh4jProtobufJar = "protobuf-java-4.35.1.jar"
val mosh4jProtobufSha256 = "a4345ba2aa009912ff6f90467fea2d104605256b72c50840d75f13256638a472"
val mosh4jSha256 = mapOf(
    "amd64" to mapOf(
        "core" to "c84e0a370417b9e6aea02506d8328458e4645926791765d87993e0848618f0f8",
        "crypto" to "bdbddf9725b24703ffa4206e14f7d2f548c0698ca718503a070c4b38ddc1b16d",
        "protocol" to "e0d4e5fe4d327bbb14d1b86d002a74a85d42a94a3dbdad77a450460c8257cba9",
        "terminal" to "a6af1e8f04fcd00af39d9c7b82528413cadae96d156a60c825c94ce6551e3287",
        "transport" to "688c0ea8281085dcf82c408a646b9f0780bf48dad14cae73eed575e5bd3c22fd"
    ),
    "arm64" to mapOf(
        "core" to "d661a615eac679367959594d0a843b8d692a6c24df3af61582b2590ddd91718d",
        "crypto" to "096e37300bddbeafbda62a19e5b433652ea128d9350ea8d73776bd899fd19478",
        "protocol" to "4c7a771ff6295afe6a72e608e20efb910dd5cab5dc48f17cc36ab6a5f31b303f",
        "terminal" to "f4f64398cda87b375327675bdb68d1205217ec2f58529c58eb4b8a7c41483c47",
        "transport" to "b9ad71cf2efafe49b1f4f46a99c6139210f260b0e909993a9d7715f7b194e691"
    )
)

fun mosh4jArch(): String = when (System.getProperty("os.arch", "").lowercase()) {
    "aarch64", "arm64" -> "arm64"
    else -> "amd64"
}

tasks.register("copyMosh4jBundled") {
    group = "build"
    description = "Downloads the current mosh4j release into an isolated native-package staging directory."
    inputs.property("version", mosh4jVersion)
    inputs.property("architecture", mosh4jArch())
    inputs.property("artifactSha256", mosh4jSha256)
    inputs.property("protobufSha256", mosh4jProtobufSha256)
    outputs.dir(bundledMosh4jDir)
    doLast {
        val arch = mosh4jArch()
        val mosh4jBase = bundledMosh4jDir.get().asFile
        delete(mosh4jBase)
        val releaseDir = mosh4jBase.resolve("release-$mosh4jVersion-$arch")
        val depsDir = mosh4jBase.resolve("deps")
        releaseDir.mkdirs()
        depsDir.mkdirs()
        val checksums = mosh4jSha256[arch]
            ?: throw GradleException("No mosh4j checksum set for architecture $arch")
        val artifacts = mutableListOf<Triple<String, java.io.File, String>>()
        for (module in mosh4jModules) {
            val jar = "mosh4j-$module-$mosh4jVersion-$arch.jar"
            val checksum = checksums[module]
                ?: throw GradleException("No mosh4j checksum for $module/$arch")
            artifacts.add(Triple("$mosh4jReleaseUrl/$jar", releaseDir.resolve(jar), checksum))
        }
        // bcprov is already a top-level application dependency. Mosh's parent-first classloader
        // reuses it, avoiding an otherwise byte-identical 8.5 MiB copy in every native package.
        artifacts.add(Triple(
            "https://repo1.maven.org/maven2/com/google/protobuf/protobuf-java/4.35.1/$mosh4jProtobufJar",
            depsDir.resolve(mosh4jProtobufJar),
            mosh4jProtobufSha256
        ))
        for ((url, file, checksum) in artifacts) {
            downloadPinned(url, file, checksum)
            try {
                if (zipTree(file).matching { include("**/*.class") }.files.isEmpty()) {
                    throw GradleException("Downloaded JAR contains no classes: ${file.name}")
                }
            } catch (failure: Exception) {
                throw GradleException("Downloaded artifact is not a valid JAR: ${file.name}", failure)
            }
        }
    }
}

tasks.register("prepareSlimRuntimeJars") {
    group = "build"
    description = "Repackages JNA and pty4j with only the native binaries for the target OS/architecture."
    inputs.files(configurations.runtimeClasspath)
    inputs.property("targetOs", osName)
    inputs.property("targetArch", formatterArch())
    outputs.dir(slimRuntimeJarDir)
    doLast {
        val outputDir = slimRuntimeJarDir.get().asFile
        delete(outputDir)
        outputDir.mkdirs()

        val arch = formatterArch()
        val jnaPrefix = when {
            isMac && arch == "arm64" -> "com/sun/jna/darwin-aarch64/"
            isMac && arch == "x64" -> "com/sun/jna/darwin-x86-64/"
            isLinux && arch == "arm64" -> "com/sun/jna/linux-aarch64/"
            isLinux && arch == "x64" -> "com/sun/jna/linux-x86-64/"
            isWindows && arch == "arm64" -> "com/sun/jna/win32-aarch64/"
            isWindows && arch == "x64" -> "com/sun/jna/win32-x86-64/"
            else -> throw GradleException("No JNA native mapping for $osName/$arch")
        }
        val ptyPrefix = when {
            isMac -> "resources/com/pty4j/native/darwin/"
            isLinux && arch == "arm64" -> "resources/com/pty4j/native/linux/aarch64/"
            isLinux && arch == "x64" -> "resources/com/pty4j/native/linux/x86-64/"
            isWindows && arch == "arm64" -> "resources/com/pty4j/native/win/aarch64/"
            isWindows && arch == "x64" -> "resources/com/pty4j/native/win/x86-64/"
            else -> throw GradleException("No pty4j native mapping for $osName/$arch")
        }

        val runtimeJars = configurations.runtimeClasspath.get().files.filter { it.extension == "jar" }
        val sources = listOf(
            runtimeJars.singleOrNull { it.name.matches(Regex("jna-[0-9].*\\.jar")) }
                ?: throw GradleException("Could not resolve the JNA runtime JAR"),
            runtimeJars.singleOrNull { it.name.matches(Regex("pty4j-[0-9].*\\.jar")) }
                ?: throw GradleException("Could not resolve the pty4j runtime JAR")
        )

        sources.forEach { sourceJar ->
            val unpackDir = layout.buildDirectory.get().asFile.resolve("tmp/slim-runtime-jars/${sourceJar.nameWithoutExtension}")
            delete(unpackDir)
            unpackDir.mkdirs()
            copy {
                from(zipTree(sourceJar))
                into(unpackDir)
                includeEmptyDirs = false
                eachFile {
                    val resourcePath = path
                    val remove = when {
                        sourceJar.name.startsWith("jna-") -> {
                            val isNative = resourcePath.matches(
                                Regex("com/sun/jna/[^/]+/(?:lib)?jnidispatch\\..+")
                            )
                            isNative && !resourcePath.startsWith(jnaPrefix)
                        }
                        else -> resourcePath.startsWith("resources/com/pty4j/native/") &&
                            !resourcePath.startsWith(ptyPrefix)
                    }
                    if (remove) exclude()
                }
            }
            if (isMac && sourceJar.name.startsWith("pty4j-")) {
                val universal = unpackDir.resolve("resources/com/pty4j/native/darwin/libpty.dylib")
                val thinned = universal.resolveSibling("libpty.thin.dylib")
                val lipoArch = if (arch == "arm64") "arm64" else "x86_64"
                val lipo = ProcessBuilder(
                    "lipo", universal.absolutePath, "-thin", lipoArch, "-output", thinned.absolutePath
                ).directory(project.rootDir).inheritIO().start()
                if (lipo.waitFor() != 0) {
                    throw GradleException("Could not thin pty4j libpty.dylib to $lipoArch")
                }
                Files.move(
                    thinned.toPath(),
                    universal.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
                val adHocSign = ProcessBuilder("codesign", "--force", "--sign", "-", universal.absolutePath)
                    .directory(project.rootDir).inheritIO().start()
                if (adHocSign.waitFor() != 0) {
                    throw GradleException("Could not ad-hoc sign thinned pty4j libpty.dylib")
                }
            }
            val nativeFiles = unpackDir.walkTopDown()
                .filter { file ->
                    file.isFile && file.extension.lowercase() in setOf("dll", "exe", "so", "dylib", "jnilib") &&
                        (file.invariantSeparatorsPath.contains("/com/sun/jna/") ||
                            file.invariantSeparatorsPath.contains("/resources/com/pty4j/native/"))
                }
                .toList()
            if (nativeFiles.isEmpty()) {
                throw GradleException("No target native binaries remained in ${sourceJar.name}")
            }
            nativeFiles.forEach { validateNativeArchitecture(it, arch) }
            val targetJar = outputDir.resolve(sourceJar.name)
            ant.withGroovyBuilder {
                "zip"("destfile" to targetJar.absolutePath, "basedir" to unpackDir.absolutePath)
            }
            delete(unpackDir)
        }

        val jnaJar = outputDir.listFiles()?.singleOrNull { it.name.startsWith("jna-") }
            ?: throw GradleException("Slim JNA JAR was not created")
        val ptyJar = outputDir.listFiles()?.singleOrNull { it.name.startsWith("pty4j-") }
            ?: throw GradleException("Slim pty4j JAR was not created")
        if (!zipTree(jnaJar).matching { include("$jnaPrefix*") }.files.any()) {
            throw GradleException("Slim JNA JAR does not contain $jnaPrefix")
        }
        if (!zipTree(ptyJar).matching { include("$ptyPrefix*") }.files.any()) {
            throw GradleException("Slim pty4j JAR does not contain $ptyPrefix")
        }
    }
}

// Deterministically assemble the complete jpackage input in one final Sync operation so stale dependency versions,
// formatter runtimes and old mosh architectures can never leak into an incremental package.
tasks.register<Sync>("prepareJpackage") {
    group = "build"
    description = "Deterministically assembles a clean, target-specific jpackage input directory."
    dependsOn(
        tasks.jar,
        "copyBundledFormatters",
        "copyMosh4jBundled",
        "prepareSlimRuntimeJars"
    )
    into(jpackageInput.map { it.dir("libs") })
    from(configurations.runtimeClasspath) {
        exclude { details ->
            details.file.name.matches(Regex("jna-[0-9].*\\.jar")) ||
                details.file.name.matches(Regex("pty4j-[0-9].*\\.jar"))
        }
    }
    from(tasks.jar.map { it.archiveFile })
    from(slimRuntimeJarDir)
    from(bundledFormatterDir) {
        into("formatters")
    }
    from(bundledMosh4jDir) {
        into("mosh4j")
        include("release-$mosh4jVersion-${mosh4jArch()}/**")
        include("deps/$mosh4jProtobufJar")
    }
    // Enterprise policy template: ships as policy/kortty-policy.toml.example next to the app jar
    // (nothing is active by default — admins copy it to kortty-policy.toml in the same folder,
    // which is exactly where PolicyLocator looks).
    from("package/policy") {
        into("policy")
    }
    doLast {
        val libsDir = jpackageInput.get().asFile.resolve("libs")
        val forbidden = listOf(
            libsDir.resolve("formatters/node"),
            libsDir.resolve("formatters/prettier"),
            libsDir.resolve("formatters/sql-formatter"),
            libsDir.resolve("mosh4j/deps/bcprov-jdk18on-1.85.2.jar")
        ).filter { it.exists() }
        if (forbidden.isNotEmpty()) {
            throw GradleException("Oversized or duplicate package inputs remain: ${forbidden.joinToString()}")
        }
    }
}

val jpackageStagingVerificationNonce = layout.buildDirectory.file("verification/jpackage-staging-seed.txt")

val seedStaleJpackageInput = tasks.register("seedStaleJpackageInput") {
    group = "verification"
    description = "Seeds obsolete package-input files for the staging-cleanliness regression test."
    doLast {
        val nonce = jpackageStagingVerificationNonce.get().asFile
        nonce.parentFile.mkdirs()
        nonce.writeText(System.nanoTime().toString())

        val libs = jpackageInput.get().asFile.resolve("libs")
        listOf(
            libs.resolve("obsolete-dependency-0.0.jar"),
            libs.resolve("formatters/node/bin/node"),
            libs.resolve("mosh4j/release-0.0.0-amd64/obsolete.jar"),
            libs.resolve("mosh4j/deps/bcprov-jdk18on-0.0.jar")
        ).forEach { marker ->
            marker.parentFile.mkdirs()
            marker.writeText("stale package input")
        }
    }
}

tasks.named("prepareJpackage") {
    mustRunAfter(seedStaleJpackageInput)
    inputs.file(jpackageStagingVerificationNonce)
        .withPropertyName("stagingVerificationNonce")
        .optional()
}

val verifyJpackageStaging = tasks.register("verifyJpackageStaging") {
    group = "verification"
    description = "Verifies that jpackage staging removes stale files and carries only target natives."
    dependsOn(seedStaleJpackageInput, "prepareJpackage")
    doLast {
        val libs = jpackageInput.get().asFile.resolve("libs")
        val forbidden = libs.walkTopDown().filter { file ->
            val relative = file.relativeTo(libs).invariantSeparatorsPath.lowercase()
            file.isFile && (
                relative.contains("obsolete") || relative.contains("formatters/node/") ||
                    relative.contains("formatters/prettier/") || relative.contains("formatters/sql-formatter/") ||
                    relative.contains("mosh4j/deps/bcprov") || file.name.contains("junit", ignoreCase = true) ||
                    file.name.contains("javafx-fxml", ignoreCase = true)
            )
        }.toList()
        if (forbidden.isNotEmpty()) {
            throw GradleException("Stale/forbidden jpackage inputs remain: ${forbidden.joinToString()}")
        }
        val moshReleaseDirs = libs.resolve("mosh4j").listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("release-") }
            .orEmpty()
        if (moshReleaseDirs.map { it.name } != listOf("release-$mosh4jVersion-${mosh4jArch()}")) {
            throw GradleException("jpackage staging contains unexpected mosh4j releases: $moshReleaseDirs")
        }
        val slimJars = listOf(
            libs.listFiles()?.singleOrNull { it.name.matches(Regex("jna-[0-9].*\\.jar")) },
            libs.listFiles()?.singleOrNull { it.name.matches(Regex("pty4j-[0-9].*\\.jar")) }
        )
        if (slimJars.any { it == null }) {
            throw GradleException("jpackage staging must contain exactly one slim JNA and pty4j JAR")
        }
        slimJars.filterNotNull().forEach { jar ->
            ZipFile(jar).use { archive ->
                val nativeEntries = archive.entries().asSequence()
                    .filter { !it.isDirectory }
                    .map { it.name }
                    .filter { name ->
                        if (jar.name.startsWith("jna-")) {
                            name.matches(Regex("com/sun/jna/[^/]+/(?:lib)?jnidispatch\\..+"))
                        } else {
                            name.startsWith("resources/com/pty4j/native/")
                        }
                    }
                    .toList()
                val expectedPrefix = if (jar.name.startsWith("jna-")) {
                    when {
                        isMac && formatterArch() == "arm64" -> "com/sun/jna/darwin-aarch64/"
                        isMac -> "com/sun/jna/darwin-x86-64/"
                        isLinux && formatterArch() == "arm64" -> "com/sun/jna/linux-aarch64/"
                        isLinux -> "com/sun/jna/linux-x86-64/"
                        isWindows && formatterArch() == "arm64" -> "com/sun/jna/win32-aarch64/"
                        else -> "com/sun/jna/win32-x86-64/"
                    }
                } else {
                    when {
                        isMac -> "resources/com/pty4j/native/darwin/"
                        isLinux && formatterArch() == "arm64" -> "resources/com/pty4j/native/linux/aarch64/"
                        isLinux -> "resources/com/pty4j/native/linux/x86-64/"
                        isWindows && formatterArch() == "arm64" -> "resources/com/pty4j/native/win/aarch64/"
                        else -> "resources/com/pty4j/native/win/x86-64/"
                    }
                }
                if (nativeEntries.isEmpty() || nativeEntries.any { !it.startsWith(expectedPrefix) }) {
                    throw GradleException("${jar.name} contains foreign native entries: $nativeEntries")
                }
            }
        }
    }
}

// Gemeinsame jpackage Parameter
fun getJpackageBaseArgs(appName: String, appVersion: String, mainJar: String, inputDir: String, outputDir: String): MutableList<String> {
    val args = mutableListOf(
        jpackageExecutable.get(),
        "--name", appName,
        "--app-version", appVersion,
        "--vendor", "korTTY",
        "--description", "SSH Client",
        "--input", inputDir,
        "--main-jar", mainJar,
        "--main-class", "de.kortty.Launcher",
        "--dest", outputDir,
        // Keep a complete base runtime for classpath apps and include extended locale data.
        // Without java.xml, logback initialization can fail at startup (org.xml.sax.InputSource).
        // jdk.jsobject provides netscape.javascript.JSObject, which JavaFX WebView's native WebKit
        // resolves via FindClass to marshal JS->Java. The compile-time jdk-jsobject artifact does NOT
        // bundle it, and jlink drops it by default, so without this every Monaco editor that returns
        // a JSObject crashes the JVM in JNI get_method_id (NULL class / NoClassDefFoundError) on macOS.
        // jdk.management provides com.sun.management.OperatingSystemMXBean, which the JVM-resource
        // relaunch (Launcher/JvmRelauncher) uses to size the heap from physical RAM.
        "--add-modules", "java.base,java.desktop,java.logging,java.management,java.naming,java.net.http,java.prefs,java.rmi,java.security.jgss,java.sql,java.xml,jdk.compiler,jdk.crypto.ec,jdk.jsobject,jdk.localedata,jdk.management,jdk.unsupported",
        // Shrink the jlink runtime image: --jlink-options REPLACES jpackage's defaults
        // (--strip-native-commands --strip-debug --no-man-pages --no-header-files), so they are
        // re-stated before the additions. zip-6 roughly halves lib/modules; --include-locales
        // keeps exactly the app's UI locales (LanguageManager.SUPPORTED_LOCALES) instead of all
        // of jdk.localedata — other OS locales fall back to English date/number formats.
        "--jlink-options",
        "--strip-native-commands --strip-debug --no-man-pages --no-header-files " +
            "--compress=zip-6 --include-locales=en,de,it,es,pt,fr,hr,nl",
        "--java-options", "-Djava.awt.headless=false",
        "--java-options", "--enable-native-access=ALL-UNNAMED",
        // Conservative memory bounds: the JVM default max heap is 25% of physical RAM (8 GB on
        // a 32 GB machine). 2 GB is generous for terminal buffers/chats/exports; WebView/WebKit
        // memory is native and NOT governed by the heap. The periodic-GC (JEP 346) and free-ratio
        // bounds uncommit idle heap so RSS shrinks back after load. G1 is deliberately NOT selected
        // explicitly here — it is the JDK default, and baking `-XX:+UseG1GC` would collide
        // ("Multiple garbage collectors selected") with the opt-in ZGC profile that the JVM-resource
        // relaunch applies via _JAVA_OPTIONS. The G1-only flags below are inert (not fatal) under ZGC.
        // Beware: an unrecognized -XX flag aborts JVM startup, i.e. the packaged app would not launch.
        "--java-options", "-Xms64m",
        "--java-options", "-Xmx2g",
        "--java-options", "-XX:G1PeriodicGCInterval=60000",
        "--java-options", "-XX:MinHeapFreeRatio=10",
        "--java-options", "-XX:MaxHeapFreeRatio=25",
        // Belt-and-braces for MacGlassQuitHook: the packaged app loads JavaFX from the class path
        // (unnamed module) so this is not strictly required, but it keeps the native-quit hook
        // working if a future packaging change ever puts JavaFX on the module path. With no
        // javafx.graphics MODULE present it degrades to an ignorable "unknown module" warning.
        "--java-options", "--add-exports=javafx.graphics/com.sun.glass.ui=ALL-UNNAMED"
    )
    googleJavaFormatJvmArgs.forEach { javaOption ->
        args.addAll(listOf("--java-options", javaOption))
    }
    return args
}

// ==================== macOS ====================
if (isMac) {
    val macSignEnabled = (findProperty("kortty.macos.sign") as String?)?.toBoolean() == true
    val macSigningIdentity = (findProperty("kortty.macos.signingIdentity") as String?)?.trim()
    val macSigningKeychain = (findProperty("kortty.macos.signingKeychain") as String?)?.trim()
    val macNativeJarPatterns = listOf(
        Regex("""^jna-[\w.\-]+\.jar$"""),
        Regex("""^pty4j-[\w.\-]+\.jar$""")
        // Do NOT add javafx-*.jar here: this task signs with `--options runtime`, and the resulting
        // hardened-runtime flag on libjfxwebkit.dylib kills JavaScriptCore's JIT (WebView dies the
        // instant JS executes, no crash report). JavaFX 21.0.0's Gluon signatures notarize as-is.
        // If a JavaFX bump ever forces re-signing, do it WITHOUT `--options runtime`. See javaFxVersion.
    )

    // Icon-Funktion für macOS: Versuche .icns, sonst verwende PNG
    fun getMacIcon(): File {
        val icnsFile = file("src/main/resources/icon/kortty_icon.icns")
        val pngFile = file("src/main/resources/icon/kortty_icon.png")
        
        return when {
            icnsFile.exists() -> icnsFile
            pngFile.exists() -> {
                println("WARNUNG: .icns Icon nicht gefunden, verwende PNG. Für bessere Ergebnisse erstelle ein .icns Icon.")
                pngFile
            }
            else -> throw GradleException("korTTY Icon nicht gefunden! Bitte erstelle src/main/resources/icon/kortty_icon.icns oder kortty_icon.png")
        }
    }

    tasks.register("signMacBundledNativeLibraries") {
        dependsOn("prepareJpackage")
        onlyIf { macSignEnabled }

        doLast {
            if (macSigningIdentity.isNullOrEmpty()) {
                throw GradleException("macOS signing is enabled but property 'kortty.macos.signingIdentity' is missing.")
            }

            val libsDir = jpackageInput.get().asFile.resolve("libs")
            val jarFiles = libsDir.listFiles()
                ?.filter { file -> macNativeJarPatterns.any { pattern -> pattern.matches(file.name) } }
                .orEmpty()

            if (jarFiles.isEmpty()) {
                throw GradleException("No bundled macOS native JARs found in ${libsDir.absolutePath}.")
            }

            val keychainArgs = if (!macSigningKeychain.isNullOrEmpty()) {
                listOf("--keychain", macSigningKeychain)
            } else {
                emptyList()
            }

            jarFiles.forEach { jarFile ->
                val tempDir = layout.buildDirectory.get().asFile
                    .resolve("mac-native-signing")
                    .resolve(jarFile.nameWithoutExtension)

                delete(tempDir)
                tempDir.mkdirs()

                copy {
                    from(zipTree(jarFile))
                    into(tempDir)
                }

                val nativeFiles = tempDir.walkTopDown()
                    .filter { it.isFile && (it.extension == "dylib" || it.extension == "jnilib") }
                    .toList()

                if (nativeFiles.isEmpty()) {
                    println("No macOS native binaries found in ${jarFile.name}; skipping repack.")
                    delete(tempDir)
                    return@forEach
                }

                nativeFiles.forEach { nativeFile ->
                    val codesignCommand = listOf(
                        "codesign",
                        "--force",
                        "--sign", macSigningIdentity,
                        "--timestamp",
                        "--options", "runtime"
                    ) + keychainArgs + listOf(nativeFile.absolutePath)

                    val codesignProcess = ProcessBuilder(codesignCommand)
                        .directory(project.rootDir)
                        .inheritIO()
                        .start()

                    if (codesignProcess.waitFor() != 0) {
                        throw GradleException("codesign failed for ${nativeFile.absolutePath}")
                    }
                }

                delete(jarFile)
                ant.withGroovyBuilder {
                    "zip"("destfile" to jarFile.absolutePath, "basedir" to tempDir.absolutePath)
                }
                delete(tempDir)
            }
        }
    }
    
    // jpackage Task für macOS .app
    tasks.register<Exec>("jpackage") {
        dependsOn("signMacBundledNativeLibraries")
        
        val appName = "korTTY"
        val appVersion = project.version.toString().replace("-SNAPSHOT", "")
        val mainJar = tasks.jar.get().archiveFileName.get()
        val inputDir = jpackageInput.get().asFile.absolutePath + "/libs"
        val outputDir = jpackageDir.get().asFile.absolutePath
        val appBundle = jpackageDir.get().asFile.resolve("$appName.app")
        val iconFile = getMacIcon()
        
        doFirst {
            file(outputDir).mkdirs()
            if (appBundle.exists()) {
                delete(appBundle)
            }
        }
        
        val args = getJpackageBaseArgs(appName, appVersion, mainJar, inputDir, outputDir)
        args.addAll(listOf(
            "--type", "app-image",
            "--mac-package-name", appName,
            "--icon", iconFile.absolutePath
        ))
        if (macSignEnabled) {
            if (macSigningIdentity.isNullOrEmpty()) {
                throw GradleException("macOS signing is enabled but property 'kortty.macos.signingIdentity' is missing.")
            }
            args.addAll(listOf(
                "--mac-sign",
                "--mac-signing-key-user-name", macSigningIdentity
            ))
            if (!macSigningKeychain.isNullOrEmpty()) {
                args.addAll(listOf("--mac-signing-keychain", macSigningKeychain))
            }
        }
        
        commandLine(args)
    }
    
    // jpackage Task für macOS .dmg Installer
    tasks.register<Exec>("jpackageDmg") {
        dependsOn("signMacBundledNativeLibraries")
        
        val appName = "korTTY"
        val appVersion = project.version.toString().replace("-SNAPSHOT", "")
        val mainJar = tasks.jar.get().archiveFileName.get()
        val inputDir = jpackageInput.get().asFile.absolutePath + "/libs"
        val outputDir = jpackageDir.get().asFile.absolutePath
        val dmgFile = jpackageDir.get().asFile.resolve("$appName-$appVersion.dmg")
        val iconFile = getMacIcon()
        
        doFirst {
            file(outputDir).mkdirs()
            if (dmgFile.exists()) {
                delete(dmgFile)
            }
        }
        
        val args = getJpackageBaseArgs(appName, appVersion, mainJar, inputDir, outputDir)
        args.addAll(listOf(
            "--type", "dmg",
            "--mac-package-name", appName,
            "--icon", iconFile.absolutePath
        ))
        if (macSignEnabled) {
            if (macSigningIdentity.isNullOrEmpty()) {
                throw GradleException("macOS signing is enabled but property 'kortty.macos.signingIdentity' is missing.")
            }
            args.addAll(listOf(
                "--mac-sign",
                "--mac-signing-key-user-name", macSigningIdentity
            ))
            if (!macSigningKeychain.isNullOrEmpty()) {
                args.addAll(listOf("--mac-signing-keychain", macSigningKeychain))
            }
        }

        commandLine(args)

        // jpackage always writes zlib-compressed (UDZO) DMGs; converting to LZMA (ULMO,
        // mountable on macOS 10.15+) shrinks the download ~15-25%. This runs before the CI
        // notarization/stapling steps that follow this task, and the conversion preserves the
        // signed .app inside bit-for-bit — only the container compression changes.
        doLast {
            val lzmaFile = File(dmgFile.parentFile, "${dmgFile.nameWithoutExtension}-ulmo.dmg")
            delete(lzmaFile)
            val process = ProcessBuilder(
                "hdiutil", "convert", dmgFile.absolutePath,
                "-format", "ULMO",
                "-o", lzmaFile.absolutePath
            ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            if (process.waitFor() != 0) {
                throw GradleException("hdiutil convert to ULMO failed:\n$output")
            }
            delete(dmgFile)
            if (!lzmaFile.renameTo(dmgFile)) {
                throw GradleException("Could not replace ${dmgFile.name} with the LZMA-converted DMG")
            }
            println("DMG converted to ULMO (LZMA): ${dmgFile.length() / (1024 * 1024)} MB")
        }
    }
}

// ==================== Windows ====================
if (isWindows) {
    // Windows icons must be .ico for jpackage; fall back to default icon if missing.
    fun getWindowsIcon(): File? {
        val icoFile = file("src/main/resources/icon/kortty_icon.ico")
        return if (icoFile.exists()) {
            icoFile
        } else {
            println("WARN: .ico icon not found. Using default Windows icon.")
            null
        }
    }
    
    // jpackage Task für Windows .exe
    tasks.register<Exec>("jpackage") {
        dependsOn("prepareJpackage")
        
        val appName = "korTTY"
        val appVersion = project.version.toString().replace("-SNAPSHOT", "")
        val mainJar = tasks.jar.get().archiveFileName.get()
        val inputDir = jpackageInput.get().asFile.absolutePath + "/libs"
        val outputDir = jpackageDir.get().asFile.absolutePath
        val iconFile = getWindowsIcon()
        
        doFirst {
            file(outputDir).mkdirs()
        }
        
        val args = getJpackageBaseArgs(appName, appVersion, mainJar, inputDir, outputDir)
        args.addAll(listOf("--type", "app-image"))
        if (iconFile != null) {
            args.addAll(listOf("--icon", iconFile.absolutePath))
        }
        
        commandLine(args)
    }
    
    // jpackage Task für Windows .msi Installer
    tasks.register<Exec>("jpackageMsi") {
        dependsOn("prepareJpackage")
        
        val appName = "korTTY"
        val appVersion = project.version.toString().replace("-SNAPSHOT", "")
        val mainJar = tasks.jar.get().archiveFileName.get()
        val inputDir = jpackageInput.get().asFile.absolutePath + "/libs"
        val outputDir = jpackageDir.get().asFile.absolutePath
        val iconFile = getWindowsIcon()
        
        doFirst {
            file(outputDir).mkdirs()
        }
        
        val args = getJpackageBaseArgs(appName, appVersion, mainJar, inputDir, outputDir)
        args.addAll(listOf(
            "--type", "msi",
            "--win-dir-chooser",
            "--win-menu",
            "--win-shortcut"
        ))
        if (iconFile != null) {
            args.addAll(listOf("--icon", iconFile.absolutePath))
        }
        
        commandLine(args)
    }
}

// ==================== Linux ====================
if (isLinux) {
    // Icon-Funktion für Linux: Verwende PNG Icon
    fun getLinuxIcon(): File {
        val pngFile = file("src/main/resources/icon/kortty_icon.png")
        
        if (!pngFile.exists()) {
            throw GradleException("korTTY Icon nicht gefunden! Bitte erstelle src/main/resources/icon/kortty_icon.png")
        }
        
        return pngFile
    }
    
    // jpackage Task für Linux App-Image
    tasks.register<Exec>("jpackage") {
        dependsOn("prepareJpackage")
        
        val appName = "korTTY"
        val appVersion = project.version.toString().replace("-SNAPSHOT", "")
        val mainJar = tasks.jar.get().archiveFileName.get()
        val inputDir = jpackageInput.get().asFile.absolutePath + "/libs"
        val outputDir = jpackageDir.get().asFile.absolutePath
        val iconFile = getLinuxIcon()
        
        doFirst {
            file(outputDir).mkdirs()
        }
        
        val args = getJpackageBaseArgs(appName, appVersion, mainJar, inputDir, outputDir)
        args.addAll(listOf(
            "--type", "app-image",
            "--icon", iconFile.absolutePath
        ))
        
        commandLine(args)
    }
    
    // jpackage Task für Linux .deb Package
    tasks.register<Exec>("jpackageDeb") {
        dependsOn("prepareJpackage")
        
        val appName = "korTTY"
        val appVersion = project.version.toString().replace("-SNAPSHOT", "")
        val mainJar = tasks.jar.get().archiveFileName.get()
        val inputDir = jpackageInput.get().asFile.absolutePath + "/libs"
        val outputDir = jpackageDir.get().asFile.absolutePath
        val iconFile = getLinuxIcon()
        
        doFirst {
            file(outputDir).mkdirs()
        }
        
        val args = getJpackageBaseArgs(appName, appVersion, mainJar, inputDir, outputDir)
        args.addAll(listOf(
            "--type", "deb",
            "--linux-package-name", appName.lowercase(),
            "--linux-shortcut",
            "--icon", iconFile.absolutePath
        ))
        
        commandLine(args)
    }
    
    // jpackage Task für Linux .rpm Package
    tasks.register<Exec>("jpackageRpm") {
        dependsOn("prepareJpackage")
        
        val appName = "korTTY"
        val appVersion = project.version.toString().replace("-SNAPSHOT", "")
        val mainJar = tasks.jar.get().archiveFileName.get()
        val inputDir = jpackageInput.get().asFile.absolutePath + "/libs"
        val outputDir = jpackageDir.get().asFile.absolutePath
        val iconFile = getLinuxIcon()
        
        doFirst {
            file(outputDir).mkdirs()
        }
        
        val args = getJpackageBaseArgs(appName, appVersion, mainJar, inputDir, outputDir)
        args.addAll(listOf(
            "--type", "rpm",
            "--linux-package-name", appName.lowercase(),
            "--linux-shortcut",
            "--icon", iconFile.absolutePath
        ))
        
        commandLine(args)
    }
}

tasks.named<JavaExec>("run") {
    val jmxEnable = project.findProperty("jmx") == "true"
    // JVM-Argumente zur Unterdrückung von JavaFX-Warnungen
    jvmArgs(listOf(
        // Öffne Zugriff auf interne JavaFX-Module (reduziert Warnungen über restricted methods)
        "--add-opens=javafx.graphics/com.sun.glass.utils=ALL-UNNAMED",
        // MacGlassQuitHook subclasses Glass's internal Application.EventHandler to catch the
        // native macOS quit; com.sun.glass.ui is a qualified export (dev run module-paths JavaFX).
        "--add-exports=javafx.graphics/com.sun.glass.ui=ALL-UNNAMED",
        "--add-opens=javafx.graphics/com.sun.javafx.tk.quantum=ALL-UNNAMED",
        "--add-opens=javafx.graphics/com.sun.marlin=ALL-UNNAMED",
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/java.security=ALL-UNNAMED",
        "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
        "--add-opens=java.base/java.io=ALL-UNNAMED",
        "--add-opens=java.base/java.nio=ALL-UNNAMED",
        // Aktiviere native Zugriffe für JavaFX (verhindert Warnungen über System::load)
        "--enable-native-access=javafx.graphics,javafx.media,javafx.web,ALL-UNNAMED",
        // Unterdrücke Warnungen über sun.misc.Unsafe::allocateMemory (von JavaFX intern verwendet)
        // Diese Warnungen kommen von JavaFX's Marlin Renderer und sind harmlos
        "--sun-misc-unsafe-memory-access=allow",
        "-Djava.awt.headless=false"
    ) + if (jmxEnable) listOf(
        // JMX remote monitoring (enable with: ./gradlew run -Pjmx=true)
        "-Dcom.sun.management.jmxremote",
        "-Dcom.sun.management.jmxremote.port=9010",
        "-Dcom.sun.management.jmxremote.local.only=false",
        "-Dcom.sun.management.jmxremote.authenticate=false"
    ) else emptyList())
    // Forward TEST_MODE_KORTTY to the forked app JVM (daemon-safe) so `TEST_MODE_KORTTY=1 ./gradlew run`
    // starts korTTY without the master-password gate.
    environment("TEST_MODE_KORTTY", providers.environmentVariable("TEST_MODE_KORTTY").getOrElse(""))
}

// ==================== Isolated llama.cpp runtime packages ====================

val llamaRuntimePlatform = when {
    isMac -> "macos"
    isWindows -> "windows"
    isLinux -> "linux"
    else -> throw GradleException("Unsupported llama.cpp runtime platform: $osName")
}
val llamaRuntimeArchitecture = when (System.getProperty("os.arch", "").lowercase()) {
    "aarch64", "arm64" -> "aarch64"
    "amd64", "x86_64", "x64" -> "x86_64"
    else -> throw GradleException("Unsupported llama.cpp runtime architecture: ${System.getProperty("os.arch")}")
}
val requestedLlamaBackend = providers.gradleProperty("llama.backend")
    .orElse(if (isMac) "METAL" else "CPU")
    .map { it.uppercase() }
val llamaBuildJobs = providers.gradleProperty("llama.jobs")
    .map { value -> value.toIntOrNull()?.coerceIn(1, 32)
        ?: throw GradleException("-Pllama.jobs must be an integer between 1 and 32.") }
    .orElse(Runtime.getRuntime().availableProcessors().coerceIn(1, 8))
val llamaSourceArchive = layout.buildDirectory.file("llama-runtime/downloads/llama.cpp-$llamaCppCommit.tar.gz")
val llamaSourceArchiveRoot = layout.buildDirectory.dir("llama-runtime/source-archive")
val llamaSourceDirectory = llamaSourceArchiveRoot.map { it.dir("llama.cpp-$llamaCppCommit") }
val llamaNativeBuildDirectory = layout.buildDirectory.dir(
    requestedLlamaBackend.map { "llama-runtime/native-$llamaRuntimePlatform-$llamaRuntimeArchitecture-${it.lowercase()}" })
val llamaRuntimeStageDirectory = layout.buildDirectory.dir(
    requestedLlamaBackend.map { "llama-runtime/stage-$llamaRuntimePlatform-$llamaRuntimeArchitecture-${it.lowercase()}" })
val llamaRuntimePackageDirectory = layout.buildDirectory.dir("llama-runtime/packages")

tasks.register("verifyLlamaCppPin") {
    group = "verification"
    description = "Validates the immutable llama.cpp tag/commit/source SHA pin."
    doLast {
        check(llamaCppTag.matches(Regex("b[0-9]+"))) { "llama.cpp tag must use the bNNNN release form." }
        check(llamaCppCommit.matches(Regex("[0-9a-f]{40}"))) { "llama.cpp commit must be a full SHA-1." }
        check(llamaCppSourceSha256.matches(Regex("[0-9a-f]{64}"))) { "llama.cpp source SHA-256 is invalid." }

        // Bind tag -> commit -> digest. downloadLlamaCppSource fetches by COMMIT and verifies the
        // SHA-256, so commit -> bytes is already bound cryptographically; what nothing else checks
        // is that the TAG names that commit. A wrong tag builds fine and mislabels the result,
        // because the tag feeds llamaRuntimeId, LLAMA_BUILD_NUMBER and the release manifest.
        val pinnedRow = llamaCppKnownPins[llamaCppTag]
            ?: throw GradleException(
                "llama.cpp tag $llamaCppTag has no pin.$llamaCppTag row in $llamaCppPinPath. " +
                    "A pin bump must add one recording that tag's upstream commit and source archive " +
                    "SHA-256; an unrecorded tag cannot be verified and is rejected instead of skipped.")
        check(pinnedRow.first == llamaCppCommit) {
            "llama.cpp $llamaCppTag is recorded at commit ${pinnedRow.first}, but the pin declares $llamaCppCommit."
        }
        check(pinnedRow.second == llamaCppSourceSha256) {
            "llama.cpp $llamaCppTag is recorded with source SHA-256 ${pinnedRow.second}, " +
                "but the pin declares $llamaCppSourceSha256."
        }
        // Validate every row, not just the active one, so a malformed entry fails on the commit that
        // introduces it rather than months later when it becomes the active pin.
        llamaCppKnownPins.forEach { (tag, row) ->
            check(tag.matches(Regex("b[0-9]+"))) { "$llamaCppPinPath: \"pin.$tag\" must use the bNNNN release form." }
            check(row.first.matches(Regex("[0-9a-f]{40}"))) { "$llamaCppPinPath: pin.$tag commit must be a full SHA-1." }
            check(row.second.matches(Regex("[0-9a-f]{64}"))) { "$llamaCppPinPath: pin.$tag SHA-256 must be 64 hex chars." }
        }
        check(llamaRuntimeRevision.matches(Regex("kortty[1-9][0-9]*"))) { "Runtime revision must be immutable (korttyN)." }
        check(requestedLlamaBackend.get() in setOf("CPU", "METAL", "VULKAN")) {
            "-Pllama.backend must be CPU, METAL, or VULKAN."
        }
        check(requestedLlamaBackend.get() != "METAL" || isMac) { "The Metal backend is available only on macOS." }
    }
}

tasks.register("downloadLlamaCppSource") {
    group = "llama runtime"
    description = "Downloads and SHA-256 verifies the pinned llama.cpp source archive."
    dependsOn("verifyLlamaCppPin")
    outputs.file(llamaSourceArchive)
    doLast {
        val destination = llamaSourceArchive.get().asFile.toPath()
        Files.createDirectories(destination.parent)

        fun sha256(path: java.nio.file.Path): String {
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path).use { input ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        if (Files.isRegularFile(destination) && sha256(destination) == llamaCppSourceSha256) {
            logger.lifecycle("Using verified cached llama.cpp source archive: $destination")
            return@doLast
        }
        Files.deleteIfExists(destination)
        val partial = destination.resolveSibling(destination.fileName.toString() + ".part")
        Files.deleteIfExists(partial)
        val sourceUri = URI("https://github.com/ggml-org/llama.cpp/archive/$llamaCppCommit.tar.gz")
        val connection = sourceUri.toURL().openConnection().apply {
            connectTimeout = 15_000
            readTimeout = 120_000
            setRequestProperty("User-Agent", "korTTY-llama-runtime-builder/$version")
        }
        try {
            connection.getInputStream().use { input -> Files.copy(input, partial) }
            val actual = sha256(partial)
            if (actual != llamaCppSourceSha256) {
                throw GradleException("llama.cpp source SHA-256 mismatch: expected $llamaCppSourceSha256, got $actual")
            }
            Files.move(partial, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(partial)
        }
    }
}

tasks.register<Sync>("extractLlamaCppSource") {
    group = "llama runtime"
    description = "Extracts the verified llama.cpp source archive."
    dependsOn("downloadLlamaCppSource")
    from(llamaSourceArchive.map { tarTree(resources.gzip(it.asFile)) })
    into(llamaSourceArchiveRoot)
    doFirst { delete(llamaSourceArchiveRoot) }
}

tasks.register<Exec>("configureLlamaRuntime") {
    group = "llama runtime"
    description = "Configures a hardened standalone llama-server build."
    dependsOn("extractLlamaCppSource")
    inputs.dir(llamaSourceDirectory)
    outputs.dir(llamaNativeBuildDirectory)
    doFirst {
        val backend = requestedLlamaBackend.get()
        val arguments = mutableListOf(
            "cmake",
            "-S", llamaSourceDirectory.get().asFile.absolutePath,
            "-B", llamaNativeBuildDirectory.get().asFile.absolutePath,
            "-DCMAKE_BUILD_TYPE=Release",
            "-DLLAMA_BUILD_NUMBER=${llamaCppTag.removePrefix("b")}",
            "-DLLAMA_BUILD_COMMIT=$llamaCppCommit",
            "-DBUILD_SHARED_LIBS=OFF",
            "-DLLAMA_BUILD_SERVER=ON",
            "-DLLAMA_BUILD_TESTS=OFF",
            "-DLLAMA_BUILD_EXAMPLES=OFF",
            "-DLLAMA_BUILD_TOOLS=ON",
            "-DLLAMA_BUILD_APP=OFF",
            "-DLLAMA_CURL=OFF",
            "-DLLAMA_BUILD_UI=OFF",
            "-DLLAMA_BUILD_WEBUI=OFF",
            "-DLLAMA_USE_PREBUILT_UI=OFF",
            "-DGGML_RPC=OFF",
            "-DGGML_NATIVE=OFF",
            "-DGGML_METAL=${if (backend == "METAL") "ON" else "OFF"}",
            "-DGGML_VULKAN=${if (backend == "VULKAN") "ON" else "OFF"}"
        )
        if (isWindows) {
            arguments += "-DCMAKE_MSVC_RUNTIME_LIBRARY=MultiThreaded"
            // Hosted Windows cannot execute the source-built UI embed helper;
            // use the signed upstream prebuilt UI asset instead.
            arguments += "-DLLAMA_USE_PREBUILT_UI=ON"
        }
        if (isMac) {
            arguments += "-DCMAKE_OSX_ARCHITECTURES=${if (llamaRuntimeArchitecture == "aarch64") "arm64" else "x86_64"}"
            arguments += "-DGGML_ACCELERATE=ON"
        }
        if (backend == "VULKAN" && isLinux) {
            // Debian's SPIR-V headers package installs its config in an
            // architecture-specific CMake directory which is not searched by
            // all runner images.
            arguments += "-DCMAKE_PREFIX_PATH=/usr/share/cmake/SPIRV-Headers;/usr/lib/${if (llamaRuntimeArchitecture == "aarch64") "aarch64-linux-gnu" else "x86_64-linux-gnu"}/cmake/SPIRV-Headers"
        }
        if (backend == "VULKAN" && isWindows) {
            val sdk = System.getenv("VULKAN_SDK")?.takeIf { it.isNotBlank() }
            if (sdk != null) {
                arguments += "-DVulkan_INCLUDE_DIR=$sdk/Include"
                arguments += "-DVulkan_LIBRARY=$sdk/Lib/vulkan-1.lib"
                arguments += "-DVulkan_GLSLC_EXECUTABLE=$sdk/Bin/glslc.exe"
            }
        }
        commandLine(arguments)
    }
}

tasks.register<Exec>("buildLlamaRuntime") {
    group = "llama runtime"
    description = "Builds the pinned static llama-server target."
    dependsOn("configureLlamaRuntime")
    inputs.dir(llamaNativeBuildDirectory)
    doFirst {
        commandLine(
            "cmake", "--build", llamaNativeBuildDirectory.get().asFile.absolutePath,
            "--config", "Release", "--target", "llama-server", "--parallel", llamaBuildJobs.get().toString())
    }
}

tasks.register<Sync>("installLlamaRuntimeStaging") {
    group = "llama runtime"
    description = "Stages llama-server separately from korTTY's application packaging inputs."
    dependsOn("buildLlamaRuntime")
    into(llamaRuntimeStageDirectory)
    from(llamaNativeBuildDirectory) {
        include("**/llama-server", "**/llama-server.exe")
        includeEmptyDirs = false
        eachFile {
            relativePath = org.gradle.api.file.RelativePath(true, "bin", name)
        }
    }
}

val packageLlamaRuntime = tasks.register<Zip>("packageLlamaRuntime") {
    group = "llama runtime"
    description = "Creates an immutable, independently downloadable llama.cpp runtime package."
    dependsOn("installLlamaRuntimeStaging")
    isReproducibleFileOrder = true
    isPreserveFileTimestamps = false
    destinationDirectory.set(llamaRuntimePackageDirectory)
    archiveFileName.set(requestedLlamaBackend.map {
        "$llamaRuntimeId-$llamaRuntimePlatform-$llamaRuntimeArchitecture-${it.lowercase()}.zip"
    })
    from(llamaRuntimeStageDirectory) {
        exclude("include/**", "lib/cmake/**", "share/**")
    }
    from(llamaSourceDirectory.map { it.file("LICENSE") }) {
        into("licenses")
        rename { "llama.cpp-LICENSE" }
    }
}

tasks.register("generateLlamaRuntimeManifest") {
    group = "llama runtime"
    description = "Writes the signed-index package descriptor input for the built runtime."
    dependsOn(packageLlamaRuntime)
    val manifestFile = requestedLlamaBackend.map {
        llamaRuntimePackageDirectory.get().file(
            "$llamaRuntimeId-$llamaRuntimePlatform-$llamaRuntimeArchitecture-${it.lowercase()}.json").asFile
    }
    outputs.file(manifestFile)
    doLast {
        val archive = packageLlamaRuntime.get().archiveFile.get().asFile.toPath()
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(archive).use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
        val backend = requestedLlamaBackend.get().lowercase()
        val entrypoint = if (isWindows) "bin/llama-server.exe" else "bin/llama-server"
        val json = """{
  "runtimeId": "$llamaRuntimeId",
  "llamaTag": "$llamaCppTag",
  "commit": "$llamaCppCommit",
  "apiContractVersion": $llamaRuntimeApiContractVersion,
  "minimumKorttyVersion": "$version",
  "platform": "$llamaRuntimePlatform",
  "architecture": "$llamaRuntimeArchitecture",
  "backend": "$backend",
  "size": ${Files.size(archive)},
  "sha256": "$sha256",
  "downloadUrl": "__PUBLISH_URL__/${archive.fileName}",
  "entrypoint": "$entrypoint",
  "revoked": false
}
"""
        val output = manifestFile.get().toPath()
        Files.createDirectories(output.parent)
        Files.writeString(output, json)
        logger.lifecycle("Runtime package: $archive")
        logger.lifecycle("Runtime descriptor: $output")
    }
}

tasks.test {
    dependsOn("copyMosh4jBundled")
    useTestNG()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    systemProperty("kortty.mosh4j.testDir", bundledMosh4jDir.get().asFile.absolutePath)
}

val packageSizeReportTest = tasks.register<Exec>("packageSizeReportTest") {
    group = "verification"
    description = "Runs the package-size report's PlantUML artifact regression tests."
    inputs.files("scripts/package-size-report.py", "scripts/test_package_size_report.py")
    workingDir(projectDir)
    if (isWindows) {
        commandLine("py", "-3", "scripts/test_package_size_report.py")
    } else {
        commandLine("python3", "scripts/test_package_size_report.py")
    }
}

val guideSegmentExtractorTest = tasks.register<Exec>("guideSegmentExtractorTest") {
    group = "verification"
    description = "Runs the guide translation-manifest extractor's regression tests."
    inputs.files("scripts/extract_guide_segments.py", "scripts/test_extract_guide_segments.py")
    inputs.dir("src/main/resources/guide/en")
    workingDir(projectDir)
    if (isWindows) {
        commandLine("py", "-3", "scripts/test_extract_guide_segments.py")
    } else {
        commandLine("python3", "scripts/test_extract_guide_segments.py")
    }
}

tasks.named("check") {
    dependsOn(verifyJpackageStaging, "slimNativeRuntimeSmoke", packageSizeReportTest,
        guideSegmentExtractorTest)
}

tasks.register<JavaExec>("slimNativeRuntimeSmoke") {
    group = "verification"
    description = "Loads JNA and starts a real PTY using only the target-specific repacked native JARs."
    dependsOn("testClasses", "prepareSlimRuntimeJars")
    mainClass.set("de.kortty.core.SlimNativeRuntimeSmoke")
    val withoutOriginalNativeJars = sourceSets.test.get().runtimeClasspath.filter { file ->
        !file.name.matches(Regex("jna-[0-9].*\\.jar")) &&
            !file.name.matches(Regex("pty4j-[0-9].*\\.jar"))
    }
    classpath = fileTree(slimRuntimeJarDir) { include("*.jar") } + withoutOriginalNativeJars
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

tasks.register<JavaExec>("monacoWebViewSmoke") {
    group = "verification"
    description = "Starts a small JavaFX WebView smoke view and verifies Monaco Blob workers."
    dependsOn("testClasses", "processResources")
    mainClass.set("de.kortty.ui.MonacoEditorPaneSmoke")
    classpath = sourceSets.test.get().runtimeClasspath
}

tasks.register<JavaExec>("sessionJournalPageWebViewSmoke") {
    group = "verification"
    description = "Drives the journal page's context menu inside the real JavaFX WebView."
    dependsOn("testClasses", "processResources")
    mainClass.set("de.kortty.ui.SessionJournalPageWebViewSmoke")
    classpath = sourceSets.test.get().runtimeClasspath
}

tasks.register<JavaExec>("webFormatterSmoke") {
    group = "verification"
    description = "Formats web and SQL samples through the bundled Node-free JavaFX WebView backend."
    dependsOn("testClasses", "processResources")
    mainClass.set("de.kortty.core.WebFormatterBackendSmoke")
    classpath = sourceSets.test.get().runtimeClasspath
}

tasks.register<JavaExec>("mermaidRendererSmoke") {
    group = "verification"
    description = "Renders Mermaid SVG/PNG through the bundled JavaFX WebView backend."
    dependsOn("testClasses", "processResources")
    mainClass.set("de.kortty.core.MermaidRenderServiceSmoke")
    classpath = sourceSets.test.get().runtimeClasspath
}

tasks.register<JavaExec>("dialogHostTabSmoke") {
    group = "verification"
    description = "Hosts a dialog pane as a main-window tab and verifies the DialogHostTab lifecycle."
    dependsOn("testClasses", "processResources")
    mainClass.set("de.kortty.ui.DialogHostTabSmoke")
    classpath = sourceSets.test.get().runtimeClasspath
}

tasks.register<JavaExec>("settingsTabScreenshotStage") {
    group = "documentation"
    description = "Shows a chosen Settings tab on screen for the docs screenshot capture " +
        "(-Pkortty.screenshotTabKey=settings.tab.<name>, default settings.tab.window)."
    dependsOn("testClasses", "processResources")
    mainClass.set("de.kortty.ui.SettingsTabScreenshotStage")
    classpath = sourceSets.test.get().runtimeClasspath
    args = listOf((findProperty("kortty.captureDoneFlag") as String?) ?: "")
    listOf("kortty.screenshotTabKey", "kortty.screenshotPaneWidth",
           "kortty.screenshotPaneHeight", "kortty.screenshotHome").forEach { key ->
        (findProperty(key) as String?)?.let { systemProperty(key, it) }
    }
}

tasks.register<JavaExec>("toolTabRenderSmoke") {
    group = "verification"
    description = "Hosts the snippet manager/editor as tabs, snapshots them and detects layout loops."
    dependsOn("testClasses", "processResources")
    mainClass.set("de.kortty.ui.ToolTabRenderSmoke")
    classpath = sourceSets.test.get().runtimeClasspath
}

tasks.register<JavaExec>("agentCompletionPopupSmoke") {
    group = "verification"
    description = "Shows the terminal AI-agent TAB history popup to verify it renders without throwing."
    dependsOn("testClasses", "processResources")
    mainClass.set("de.kortty.ui.TerminalAgentCompletionPopupSmoke")
    classpath = sourceSets.test.get().runtimeClasspath
}

tasks.register<JavaExec>("aiAgentSidePanelSmoke") {
    group = "verification"
    description = "Re-parents an AI-agent panel between bottom tabs and a side stacked dock to verify it."
    dependsOn("testClasses", "processResources")
    mainClass.set("de.kortty.ui.AiAgentSidePanelSmoke")
    classpath = sourceSets.test.get().runtimeClasspath
}

tasks.register<JavaExec>("terminalSplitTransparencySmoke") {
    group = "verification"
    description = "Verifies terminal background transparency survives current and newly nested splits."
    dependsOn("testClasses", "processResources")
    mainClass.set("de.kortty.ui.TerminalSplitTransparencySmoke")
    classpath = sourceSets.test.get().runtimeClasspath
}

tasks.register<JavaExec>("terminalShortcutKeyTypedSmoke") {
    group = "verification"
    description = "Verifies shortcut-chord KEY_TYPED characters (e.g. Cmd+Shift+D) reach neither pty nor broadcast panes."
    dependsOn("testClasses", "processResources")
    mainClass.set("de.kortty.ui.TerminalShortcutKeyTypedSmoke")
    classpath = sourceSets.test.get().runtimeClasspath
}

tasks.register<JavaExec>("aiManagerModelComboSmoke") {
    group = "verification"
    description = "Selects a model in the real AI Manager model picker to verify the choice sticks."
    dependsOn("testClasses", "processResources")
    mainClass.set("de.kortty.ui.AiManagerModelComboSmoke")
    classpath = sourceSets.test.get().runtimeClasspath
}

tasks.register<JavaExec>("aiManagerReasoningPersistenceSmoke") {
    group = "verification"
    description = "Loads AI Manager profiles and verifies the stored reasoning level survives loading, profile switching and the close-time snapshot."
    dependsOn("testClasses", "processResources")
    mainClass.set("de.kortty.ui.AiManagerReasoningPersistenceSmoke")
    classpath = sourceSets.test.get().runtimeClasspath
}

tasks.register<JavaExec>("aiManagerRequestTimeoutSmoke") {
    group = "verification"
    description = "Verifies the AI Manager keeps \"follow the global timeout\" and \"never time out\" apart across loading, profile switching and the close-time snapshot."
    dependsOn("testClasses", "processResources")
    mainClass.set("de.kortty.ui.AiManagerRequestTimeoutSmoke")
    classpath = sourceSets.test.get().runtimeClasspath
}

tasks.register<JavaExec>("inputHardeningSelectorSmoke") {
    group = "verification"
    description = "Verifies the Input hardening panel's master toggle gates the sub-option grid, the size row and the per-run config."
    dependsOn("testClasses", "processResources")
    mainClass.set("de.kortty.ui.InputHardeningSelectorSmoke")
    classpath = sourceSets.test.get().runtimeClasspath
}

tasks.register<JavaExec>("aiManagerNavigationSmoke") {
    group = "verification"
    description = "Moves focus into AI Manager content and verifies the selected primary tab remains visibly marked."
    dependsOn("testClasses", "processResources")
    mainClass.set("de.kortty.ui.AiManagerNavigationSmoke")
    classpath = sourceSets.test.get().runtimeClasspath
}

tasks.register<JavaExec>("localAiWizardEmbeddingSmoke") {
    group = "verification"
    description = "Opens the local-AI setup wizard and verifies the embedding role offers the full model catalog."
    dependsOn("testClasses", "processResources")
    mainClass.set("de.kortty.ui.LocalAiSetupWizardEmbeddingSmoke")
    classpath = sourceSets.test.get().runtimeClasspath
}

tasks.register<JavaExec>("savedChatsDialogSmoke") {
    group = "verification"
    description = "Opens the standalone saved-chats window and verifies its two primary tabs render."
    dependsOn("testClasses", "processResources")
    mainClass.set("de.kortty.ui.SavedChatsDialogSmoke")
    classpath = sourceSets.test.get().runtimeClasspath
}

tasks.register<JavaExec>("localModelDownloadStatusSmoke") {
    group = "verification"
    description = "Renders synthetic local-model download progress and verifies the fixed bottom status panel."
    dependsOn("testClasses", "processResources")
    mainClass.set("de.kortty.ui.LocalModelDownloadStatusSmoke")
    classpath = sourceSets.test.get().runtimeClasspath
}

tasks.register<JavaExec>("mainWindowTranslationIndicatorSmoke") {
    group = "verification"
    description = "Renders the main window while a guide translation runs and checks the menu-bar indicator."
    dependsOn("testClasses", "processResources")
    mainClass.set("de.kortty.ui.MainWindowTranslationIndicatorSmoke")
    classpath = sourceSets.test.get().runtimeClasspath
    environment("TEST_MODE_KORTTY", "1")
    standardOutput = System.out
    errorOutput = System.err
}

tasks.register<JavaExec>("translationTabSmoke") {
    group = "verification"
    description = "Builds the Settings Translation tab and asserts the guide-translation section is present and wired."
    dependsOn("testClasses", "processResources")
    mainClass.set("de.kortty.ui.TranslationTabSmoke")
    classpath = sourceSets.test.get().runtimeClasspath
}

tasks.register<JavaExec>("generatedGuideRenderSmoke") {
    group = "verification"
    description = "Generates a guide language into build/smoke and verifies it renders with its own styling in a WebView."
    dependsOn("testClasses", "processResources")
    mainClass.set("de.kortty.ui.GeneratedGuideRenderSmoke")
    classpath = sourceSets.test.get().runtimeClasspath
    standardOutput = System.out
    errorOutput = System.err
}

tasks.register<JavaExec>("guideTranslationBench") {
    group = "verification"
    description = "Measures speed and quality of translating the bundled guide with the configured local AI profile."
    dependsOn("testClasses", "processResources")
    mainClass.set("de.kortty.core.GuideTranslationBench")
    classpath = sourceSets.test.get().runtimeClasspath
    // A local model is slow; let the reader watch instead of buffering for minutes.
    standardOutput = System.out
    errorOutput = System.err
}

tasks.register<JavaExec>("i18nTranslationBench") {
    group = "verification"
    description = "Measures speed and quality of translating the UI language bundle with the configured local AI profile."
    dependsOn("testClasses", "processResources")
    mainClass.set("de.kortty.core.I18nTranslationBench")
    classpath = sourceSets.test.get().runtimeClasspath
    // A local model is slow; let the reader watch instead of buffering for minutes.
    standardOutput = System.out
    errorOutput = System.err
}

tasks.register<JavaExec>("guideAskPanelSmoke") {
    group = "verification"
    description = "Renders the guide AI-search panel with a sample answer and snapshots it to build/smoke/guide-ask-panel.png."
    dependsOn("testClasses", "processResources")
    mainClass.set("de.kortty.ui.GuideAskPanelSmoke")
    classpath = sourceSets.test.get().runtimeClasspath
}

tasks.register<JavaExec>("guideBackgroundVisibilitySmoke") {
    group = "verification"
    description = "Verifies the guide WebView remains rendered after the window stays in the background."
    dependsOn("testClasses", "processResources")
    mainClass.set("de.kortty.ui.GuideBackgroundVisibilitySmoke")
    classpath = sourceSets.test.get().runtimeClasspath
}

tasks.register<JavaExec>("swarmStatusStripSmoke") {
    group = "verification"
    description = "Renders the AI-swarm status strip in every agent state and snapshots it to build/smoke/swarm-strip-*.png."
    dependsOn("testClasses", "processResources")
    mainClass.set("de.kortty.ui.SwarmStatusStripSmoke")
    classpath = sourceSets.test.get().runtimeClasspath
}

tasks.register<JavaExec>("aiChatRedesignSmoke") {
    group = "verification"
    description = "Renders the redesigned AI chat for every color profile and snapshots it to build/smoke/ai-chat-*.png."
    dependsOn("testClasses", "processResources")
    mainClass.set("de.kortty.ui.AiChatRedesignSmoke")
    classpath = sourceSets.test.get().runtimeClasspath
}

tasks.register<JavaExec>("resourcesTabSmoke") {
    group = "verification"
    description = "Renders Settings > Resources, asserts the max-heap line for every profile, snapshots to build/smoke/resources-tab.png."
    dependsOn("testClasses", "processResources")
    mainClass.set("de.kortty.ui.ResourcesTabSmoke")
    classpath = sourceSets.test.get().runtimeClasspath
}

tasks.register<JavaExec>("quickConnectScrollSmoke") {
    group = "verification"
    description = "Expands Quick Connect's collapsible sections in a short window and asserts the content scroll bar engages; snapshots build/smoke/quick-connect-scroll.png."
    dependsOn("testClasses", "processResources")
    mainClass.set("de.kortty.ui.QuickConnectScrollSmoke")
    classpath = sourceSets.test.get().runtimeClasspath
}

tasks.register<JavaExec>("updateDownloadCompleteSmoke") {
    group = "verification"
    description = "Renders the update 'download complete' dialog and snapshots it to build/smoke/update-download-complete.png."
    dependsOn("testClasses", "processResources")
    mainClass.set("de.kortty.ui.UpdateDownloadCompleteSmoke")
    classpath = sourceSets.test.get().runtimeClasspath
}

tasks.register<JavaExec>("snippetCodeAnalysisDialogSizingSmoke") {
    group = "verification"
    description = "Shrinks the Full-code-analysis window and verifies its Apply/Close buttons stay on screen."
    dependsOn("testClasses", "processResources")
    mainClass.set("de.kortty.ui.SnippetCodeAnalysisDialogSizingSmoke")
    classpath = sourceSets.test.get().runtimeClasspath
    environment("TEST_MODE_KORTTY", "1")
}

tasks.register<JavaExec>("snippetAiDialogsSmoke") {
    group = "verification"
    description = "Builds the unified snippet AI dialogs (review, describe, alternatives, diff) with the profile picker and re-run enabled and snapshots each to build/smoke/snippet-ai-*.png."
    dependsOn("testClasses", "processResources")
    mainClass.set("de.kortty.ui.SnippetAiDialogsSmoke")
    classpath = sourceSets.test.get().runtimeClasspath
}

tasks.register<JavaExec>("generateDesignPreviews") {
    group = "build"
    description = "Renders the Settings > Appearance preview thumbnails for every app design via Scene.snapshot."
    dependsOn("testClasses", "processResources")
    mainClass.set("de.kortty.ui.AppDesignPreviewGenerator")
    classpath = sourceSets.test.get().runtimeClasspath
}

tasks.register<JavaExec>("terminalEffectPreviewSmoke") {
    group = "verification"
    description = "Starts every built-in terminal effect preview and snapshots each to build/smoke/terminal-effect-<id>.png."
    dependsOn("testClasses", "processResources")
    mainClass.set("de.kortty.ui.TerminalEffectPreviewSmoke")
    classpath = sourceSets.test.get().runtimeClasspath
}

tasks.register<JavaExec>("generateAiSkillsTabScreenshot") {
    group = "build"
    description = "Renders the AI Manager > AI Skills screenshot for the manual via Node.snapshot."
    dependsOn("testClasses", "processResources")
    mainClass.set("de.kortty.ui.AiSkillsTabScreenshotGenerator")
    classpath = sourceSets.test.get().runtimeClasspath
}

tasks.register<JavaExec>("generateSessionJournalScreenshots") {
    group = "build"
    description = "Renders the journal export-options and marker screenshots for the manual."
    dependsOn("testClasses", "processResources")
    mainClass.set("de.kortty.ui.SessionJournalScreenshotGenerator")
    classpath = sourceSets.test.get().runtimeClasspath
}

tasks.register<JavaExec>("generatePrivacyTabScreenshot") {
    group = "build"
    description = "Renders the Settings > Privacy tab screenshot for the manual via Scene.snapshot."
    dependsOn("testClasses", "processResources")
    mainClass.set("de.kortty.ui.PrivacyTabScreenshotGenerator")
    classpath = sourceSets.test.get().runtimeClasspath
}

tasks.jar {
    val implementationTitle = project.name
    val implementationVersion = project.version
    val runtimeClasspath = configurations.runtimeClasspath
    doFirst {
        manifest {
            attributes(
                "Main-Class" to "de.kortty.KorTTYApplication",
                "Implementation-Title" to implementationTitle,
                "Implementation-Version" to implementationVersion,
                // Class-Path for all dependencies (resolved at execution time)
                "Class-Path" to runtimeClasspath.get().files.joinToString(" ") { it.name }
            )
        }
    }
}

if (isMac) {
    tasks.register<Exec>("runMacApp") {
        group = "application"
        description = "Build the macOS app bundle with jpackage and open the generated .app."
        dependsOn("jpackage")

        val appBundle = jpackageDir.get().asFile.resolve("korTTY.app")
        commandLine("open", appBundle.absolutePath)
    }
}
