plugins {
    id("java")
    id("application")
    id("org.openjfx.javafxplugin") version "0.1.0"
}

import org.gradle.jvm.toolchain.JvmVendorSpec
import java.net.URI
import java.security.MessageDigest

group = "de.kortty"
version = "2.2.0"

java {
    // Allows CI to pin a compatible toolchain per runner when needed.
    val toolchainVersion = (findProperty("kortty.javaVersion") as String?)?.toIntOrNull() ?: 25
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(toolchainVersion))
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

javafx {
    version = "21"
    modules = listOf("javafx.controls", "javafx.fxml", "javafx.graphics", "javafx.swing", "javafx.web")
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

tasks.named<ProcessResources>("processResources") {
    dependsOn(motherTerminalEffectPluginJar)
    from(motherTerminalEffectPluginJar.flatMap { it.archiveFile }) {
        into("bundled-plugins/terminal-effects")
    }
}

dependencies {
    // SSH
    implementation("org.apache.sshd:sshd-core:2.12.0")
    implementation("org.apache.sshd:sshd-common:2.12.0")
    implementation("org.apache.sshd:sshd-sftp:2.12.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")
    
    // ED25519 (EdDSA) key support for SSH
    implementation("net.i2p.crypto:eddsa:0.3.0")
    
    // SithTermFX - Terminal emulator for JavaFX (built from source by installSithtermfxLocal)
    implementation("com.sithtermfx:sithtermfx-core:1.1.0")
    implementation("com.sithtermfx:sithtermfx-ui:1.1.0")
    
    // Lanterna - Text-based terminal emulator with better zoom support
    implementation("com.googlecode.lanterna:lanterna:3.1.2")
    
    // XML Binding (JAXB)
    implementation("jakarta.xml.bind:jakarta.xml.bind-api:4.0.0")
    runtimeOnly("org.glassfish.jaxb:jaxb-runtime:4.0.4")
    
    // ZIP encryption
    implementation("net.lingala.zip4j:zip4j:2.11.5")
    
    // Archive support (TAR.BZ2, 7z)
    implementation("org.apache.commons:commons-compress:1.25.0")
    implementation("org.tukaani:xz:1.9")
    
    // RichTextFX - Code editor with syntax highlighting
    implementation("org.fxmisc.richtext:richtextfx:0.11.3")
    
    // jfiglet - ASCII art banners (FIGfonts)
    implementation("com.github.lalyos:jfiglet:0.0.9")
    
    // Password strength (zxcvbn – offline, no network)
    implementation("com.nulab-inc:zxcvbn:1.9.0")
    
    // JSON parsing for translation API responses
    implementation("com.google.code.gson:gson:2.13.2")
    implementation("com.knuddels:jtokkit:1.1.0")
    implementation("org.apache.pdfbox:pdfbox:3.0.6")
    implementation("com.google.googlejavaformat:google-java-format:1.35.0")

    // PTY support for native Mosh client
    implementation("org.jetbrains.pty4j:pty4j:0.12.25")

    // Logging
    implementation("org.slf4j:slf4j-api:2.0.9")
    implementation("ch.qos.logback:logback-classic:1.4.14")
    
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
    systemProperty("kortty.formatters.dir", layout.buildDirectory.dir("jpackage-input/libs/formatters").get().asFile.absolutePath)
}

// ==================== SithTermFX from source (no GitHub token required) ====================

val sithtermfxVersion = "1.1.0"
val sithtermfxDir = layout.projectDirectory.dir("vendor/sithtermfx")

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

val mavenLocalSithtermfxCore = File(System.getProperty("user.home"), ".m2/repository/com/sithtermfx/sithtermfx-core/1.1.0/sithtermfx-core-1.1.0.jar")

tasks.register<Exec>("installSithtermfxLocal") {
    group = "build"
    description = "Build SithTermFX from source and install to local Maven repo (requires Maven)."
    dependsOn("cloneSithtermfx")
    workingDir(sithtermfxDir)
    // Use SITHTERMFX_JDK_HOME or -Psithtermfx.jdkHome for Maven (CI may build SithTermFX in workflow instead)
    val jdkHome = project.findProperty("sithtermfx.jdkHome")?.toString()?.takeIf { it.isNotBlank() }
        ?: System.getenv("SITHTERMFX_JDK_HOME")
        ?: System.getenv("JAVA_HOME")
    if (jdkHome != null) {
        environment("JAVA_HOME", jdkHome)
    }
    commandLine("mvn", "-q", "-DskipTests", "install")
    onlyIf {
        sithtermfxDir.asFile.resolve("pom.xml").isFile && !mavenLocalSithtermfxCore.exists()
    }
}

tasks.named("compileJava") {
    dependsOn("installSithtermfxLocal")
}

// ==================== jpackage Konfiguration ====================

val jpackageDir = layout.buildDirectory.dir("jpackage")
val jpackageInput = layout.buildDirectory.dir("jpackage-input")

// Betriebssystem erkennen
val osName = System.getProperty("os.name").lowercase()
val isWindows = osName.contains("windows")
val isMac = osName.contains("mac")
val isLinux = osName.contains("linux")

// ==================== Gebuendelte Code-Formatter ====================

val formatterDownloadDir = layout.buildDirectory.dir("formatter-downloads")
val bundledFormatterDir = jpackageInput.map { it.dir("libs/formatters") }
val formatterNodeVersion = "24.15.0"
val formatterShfmtVersion = "3.13.1"
val formatterPrettierVersion = "3.6.2"
val formatterSqlFormatterVersion = "15.7.3"
val formatterPerlTidyVersion = "20260204"

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

fun stripFirstPathSegment(details: org.gradle.api.file.FileCopyDetails) {
    val segments = details.relativePath.segments
    if (segments.size > 1) {
        details.relativePath = org.gradle.api.file.RelativePath(true, *segments.drop(1).toTypedArray())
    }
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

tasks.register("copyBundledNode") {
    group = "build"
    description = "Downloads pinned Node.js LTS and copies it into the jpackage formatter directory."
    doLast {
        val archiveName = nodeArchiveName()
        if (archiveName == null) {
            logger.warn("No bundled Node.js archive configured for ${System.getProperty("os.name")} ${System.getProperty("os.arch")}; skipping.")
            return@doLast
        }
        val downloadBase = "https://nodejs.org/dist/v$formatterNodeVersion"
        val shasumsFile = formatterDownloadDir.get().asFile.resolve("node-v$formatterNodeVersion-SHASUMS256.txt")
        // Trust model: Node's SHASUMS256.txt is accepted only through HTTPS here; it is not
        // independently signature-verified, so a compromised TLS endpoint could affect expected.
        download("$downloadBase/SHASUMS256.txt", shasumsFile)
        val expected = shasumsFile.readLines()
            .firstOrNull { it.endsWith("  $archiveName") || it.endsWith(" *$archiveName") }
            ?.substringBefore(" ")
            ?: throw GradleException("No Node.js checksum found for $archiveName")
        val archive = formatterDownloadDir.get().asFile.resolve(archiveName)
        downloadPinned("$downloadBase/$archiveName", archive, expected)

        val unpackDir = layout.buildDirectory.get().asFile.resolve("formatter-node-unpack")
        delete(unpackDir)
        unpackDir.mkdirs()
        copy {
            from(if (archiveName.endsWith(".zip")) zipTree(archive) else tarTree(resources.gzip(archive)))
            into(unpackDir)
        }
        val unpackedRoot = unpackDir.listFiles()?.singleOrNull { it.isDirectory }
            ?: throw GradleException("Could not find unpacked Node.js root for $archiveName")
        val target = bundledFormatterDir.get().asFile.resolve("node")
        delete(target)
        copy {
            from(unpackedRoot)
            into(target)
        }
        val nodeExecutable = if (isWindows) target.resolve("node.exe") else target.resolve("bin/node")
        nodeExecutable.setExecutable(true, false)
    }
}

tasks.register("copyBundledPrettier") {
    group = "build"
    description = "Downloads pinned Prettier and copies it into the jpackage formatter directory."
    doLast {
        val archive = formatterDownloadDir.get().asFile.resolve("prettier-$formatterPrettierVersion.tgz")
        downloadPinned(
            "https://registry.npmjs.org/prettier/-/prettier-$formatterPrettierVersion.tgz",
            archive,
            "bc81ab83674f175a8601b7d013786f48ec2507dd4a5fcf3415831ff13a875bdf"
        )
        val target = bundledFormatterDir.get().asFile.resolve("prettier")
        delete(target)
        copy {
            from(tarTree(resources.gzip(archive))) {
                eachFile { stripFirstPathSegment(this) }
                includeEmptyDirs = false
            }
            into(target)
        }
    }
}

tasks.register("copyBundledSqlFormatter") {
    group = "build"
    description = "Downloads pinned sql-formatter and copies it into the jpackage formatter directory."
    doLast {
        val archive = formatterDownloadDir.get().asFile.resolve("sql-formatter-$formatterSqlFormatterVersion.tgz")
        downloadPinned(
            "https://registry.npmjs.org/sql-formatter/-/sql-formatter-$formatterSqlFormatterVersion.tgz",
            archive,
            "5ec54da8958d4ad9f6c948a8032ce55a2444361a9a9223766f8b4e75d2b29819"
        )
        val target = bundledFormatterDir.get().asFile.resolve("sql-formatter")
        delete(target)
        copy {
            from(tarTree(resources.gzip(archive))) {
                eachFile { stripFirstPathSegment(this) }
                includeEmptyDirs = false
            }
            into(target)
        }
        target.resolve("kortty-sql-formatter.cjs").writeText(
            """
            const fs = require("fs");
            const sqlFormatter = require("./dist/sql-formatter.min.cjs");
            const input = fs.readFileSync(0, "utf8");
            process.stdout.write(sqlFormatter.format(input));
            """.trimIndent() + "\n"
        )
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
            google-java-format.version=1.35.0
            google-java-format.source=https://central.sonatype.com/artifact/com.google.googlejavaformat/google-java-format/1.35.0
            node.version=$formatterNodeVersion
            node.source=https://nodejs.org/dist/v$formatterNodeVersion/
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
    description = "Copies all pinned formatter runtimes and packages into the jpackage input."
    dependsOn(
        "copyDependencies",
        "copyJar",
        "copyMosh4jBundled",
        "copyBundledShfmt",
        "copyBundledNode",
        "copyBundledPrettier",
        "copyBundledSqlFormatter",
        "copyBundledPerlTidy",
        "copyBundledFormatterManifest"
    )
}

// Task zum Sammeln aller Dependencies in einem Verzeichnis
tasks.register<Copy>("copyDependencies") {
    from(configurations.runtimeClasspath)
    into(jpackageInput.map { it.dir("libs") })
}

// Task zum Kopieren des eigenen JARs
tasks.register<Copy>("copyJar") {
    dependsOn(tasks.jar)
    from(tasks.jar.map { it.archiveFile })
    into(jpackageInput.map { it.dir("libs") })
}

// mosh4j release to bundle (must match Mosh4jTtyConnector.MOSH4J_RELEASE_TAG)
val mosh4jReleaseTag = "2.0.0"
val mosh4jReleaseUrl = "https://github.com/chardonnay/mosh4j/releases/download/$mosh4jReleaseTag"
val mosh4jModules = listOf("protocol", "crypto", "transport", "terminal", "core")

tasks.register("copyMosh4jBundled") {
    group = "build"
    description = "Download mosh4j release JARs and deps into jpackage libs so the app ships with mosh4j (no user download)."
    dependsOn("copyDependencies") // ensures jpackage-input/libs exists
    doLast {
        val arch = when (System.getProperty("os.arch", "").lowercase()) {
            "aarch64", "arm64" -> "arm64"
            else -> "amd64"
        }
        val libsDir = layout.buildDirectory.get().asFile.resolve("jpackage-input").resolve("libs")
        val mosh4jBase = libsDir.resolve("mosh4j")
        val releaseDir = mosh4jBase.resolve("release-$mosh4jReleaseTag-$arch")
        val depsDir = mosh4jBase.resolve("deps")
        releaseDir.mkdirs()
        depsDir.mkdirs()
        val urls = mutableListOf<Pair<String, java.io.File>>()
        for (module in mosh4jModules) {
            val jar = "mosh4j-$module-$mosh4jReleaseTag-$arch.jar"
            urls.add("$mosh4jReleaseUrl/$jar" to releaseDir.resolve(jar))
        }
        urls.add(
            "https://repo1.maven.org/maven2/org/bouncycastle/bcprov-jdk18on/1.78.1/bcprov-jdk18on-1.78.1.jar"
                to depsDir.resolve("bcprov-jdk18on-1.78.1.jar")
        )
        urls.add(
            "https://repo1.maven.org/maven2/com/google/protobuf/protobuf-java/4.28.2/protobuf-java-4.28.2.jar"
                to depsDir.resolve("protobuf-java-4.28.2.jar")
        )
        for ((url, file) in urls) {
            if (file.exists()) continue
            val proc = ProcessBuilder("curl", "-L", "-o", file.absolutePath, url)
                .directory(file.parentFile)
                .inheritIO()
                .start()
            if (proc.waitFor() != 0) throw GradleException("curl failed for $url")
        }
    }
}

listOf(
    "copyBundledShfmt",
    "copyBundledNode",
    "copyBundledPrettier",
    "copyBundledSqlFormatter",
    "copyBundledPerlTidy",
    "copyBundledFormatterManifest"
).forEach { formatterTaskName ->
    tasks.named(formatterTaskName) {
        mustRunAfter("copyDependencies", "copyJar", "copyMosh4jBundled")
    }
}

// Task zum Vorbereiten der jpackage Eingabe
tasks.register("prepareJpackage") {
    dependsOn("copyBundledFormatters")
}

// Gemeinsame jpackage Parameter
fun getJpackageBaseArgs(appName: String, appVersion: String, mainJar: String, inputDir: String, outputDir: String): MutableList<String> {
    val args = mutableListOf(
        "jpackage",
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
        "--add-modules", "java.base,java.desktop,java.logging,java.management,java.naming,java.net.http,java.prefs,java.rmi,java.scripting,java.security.jgss,java.sql,java.xml,jdk.compiler,jdk.crypto.ec,jdk.localedata,jdk.unsupported",
        "--java-options", "-Djava.awt.headless=false",
        "--java-options", "--enable-native-access=ALL-UNNAMED"
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
    jvmArgs = listOf(
        // Öffne Zugriff auf interne JavaFX-Module (reduziert Warnungen über restricted methods)
        "--add-opens=javafx.graphics/com.sun.glass.utils=ALL-UNNAMED",
        "--add-opens=javafx.graphics/com.sun.javafx.tk.quantum=ALL-UNNAMED",
        "--add-opens=javafx.graphics/com.sun.marlin=ALL-UNNAMED",
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/java.security=ALL-UNNAMED",
        "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
        "--add-opens=java.base/java.io=ALL-UNNAMED",
        "--add-opens=java.base/java.nio=ALL-UNNAMED",
        // Aktiviere native Zugriffe für JavaFX (verhindert Warnungen über System::load)
        "--enable-native-access=javafx.graphics,ALL-UNNAMED",
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
    ) else emptyList()
}

tasks.test {
    useTestNG()
}

tasks.jar {
    doFirst {
        manifest {
            attributes(
                "Main-Class" to "de.kortty.KorTTYApplication",
                "Implementation-Title" to project.name,
                "Implementation-Version" to project.version,
                // Class-Path for all dependencies (resolved at execution time)
                "Class-Path" to configurations.runtimeClasspath.get().files.joinToString(" ") { it.name }
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
