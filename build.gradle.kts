plugins {
    id("java")
    id("application")
    id("org.openjfx.javafxplugin") version "0.1.0"
}

import org.gradle.jvm.toolchain.JvmVendorSpec

group = "de.kortty"
version = "1.8.1"

java {
    // Allows CI to pin a compatible toolchain per runner when needed.
    val toolchainVersion = (findProperty("kortty.javaVersion") as String?)?.toIntOrNull() ?: 25
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(toolchainVersion))
        // Explicitly avoid IBM_SEMERU which is not supported in Gradle 9.2.1
        vendor.set(JvmVendorSpec.ADOPTIUM)
    }
}

val useSithtermfxFromPackages = !(System.getenv("GITHUB_TOKEN")?.isNotBlank() == true)

repositories {
    mavenLocal()
    if (useSithtermfxFromPackages) {
        // Not in CI: only mavenLocal (after installSithtermfxLocal) and mavenCentral
    } else {
        // CI: resolve SithTermFX 1.1.0 from GitHub Packages (faster than building from source)
        maven {
            url = uri("https://maven.pkg.github.com/chardonnay/SithTermFX")
            credentials {
                username = System.getenv("GITHUB_ACTOR") ?: ""
                password = System.getenv("GITHUB_TOKEN") ?: ""
            }
        }
    }
    mavenCentral()
    // JetBrains repository for pty4j and its dependencies
    maven {
        url = uri("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies")
    }
}

javafx {
    version = "21"
    modules = listOf("javafx.controls", "javafx.fxml", "javafx.graphics", "javafx.swing")
}

dependencies {
    // SSH
    implementation("org.apache.sshd:sshd-core:2.12.0")
    implementation("org.apache.sshd:sshd-common:2.12.0")
    implementation("org.apache.sshd:sshd-sftp:2.12.0")
    
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

    // PTY support for native Mosh client
    implementation("org.jetbrains.pty4j:pty4j:0.12.25")

    // Logging
    implementation("org.slf4j:slf4j-api:2.0.9")
    implementation("ch.qos.logback:logback-classic:1.4.14")
    
    // Testing
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.2")
}

application {
    mainClass.set("de.kortty.KorTTYApplication")
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

tasks.register<Exec>("installSithtermfxLocal") {
    group = "build"
    description = "Build SithTermFX from source and install to local Maven repo (requires Maven)."
    dependsOn("cloneSithtermfx")
    workingDir(sithtermfxDir)
    commandLine("mvn", "-q", "-DskipTests", "install")
    onlyIf { sithtermfxDir.asFile.resolve("pom.xml").isFile }
}

tasks.named("compileJava") {
    if (useSithtermfxFromPackages) {
        dependsOn("installSithtermfxLocal")
    }
}

// ==================== jpackage Konfiguration ====================

val jpackageDir = layout.buildDirectory.dir("jpackage")
val jpackageInput = layout.buildDirectory.dir("jpackage-input")

// Betriebssystem erkennen
val osName = System.getProperty("os.name").lowercase()
val isWindows = osName.contains("windows")
val isMac = osName.contains("mac")
val isLinux = osName.contains("linux")

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

// Task zum Vorbereiten der jpackage Eingabe
tasks.register("prepareJpackage") {
    dependsOn("copyDependencies", "copyJar", "copyMosh4jBundled")
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
        "--add-modules", "java.base,java.desktop,java.logging,java.management,java.naming,java.net.http,java.prefs,java.scripting,java.security.jgss,java.sql,java.xml,jdk.crypto.ec,jdk.localedata,jdk.unsupported",
        "--java-options", "-Djava.awt.headless=false",
        "--java-options", "--enable-native-access=ALL-UNNAMED"
    )
    return args
}

// ==================== macOS ====================
if (isMac) {
    val macSignEnabled = (findProperty("kortty.macos.sign") as String?)?.toBoolean() == true
    val macSigningIdentity = (findProperty("kortty.macos.signingIdentity") as String?)?.trim()
    val macSigningKeychain = (findProperty("kortty.macos.signingKeychain") as String?)?.trim()

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
    
    // jpackage Task für macOS .app
    tasks.register<Exec>("jpackage") {
        dependsOn("prepareJpackage")
        
        val appName = "korTTY"
        val appVersion = project.version.toString().replace("-SNAPSHOT", "")
        val mainJar = tasks.jar.get().archiveFileName.get()
        val inputDir = jpackageInput.get().asFile.absolutePath + "/libs"
        val outputDir = jpackageDir.get().asFile.absolutePath
        val iconFile = getMacIcon()
        
        doFirst {
            file(outputDir).mkdirs()
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
        dependsOn("prepareJpackage")
        
        val appName = "korTTY"
        val appVersion = project.version.toString().replace("-SNAPSHOT", "")
        val mainJar = tasks.jar.get().archiveFileName.get()
        val inputDir = jpackageInput.get().asFile.absolutePath + "/libs"
        val outputDir = jpackageDir.get().asFile.absolutePath
        val iconFile = getMacIcon()
        
        doFirst {
            file(outputDir).mkdirs()
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
    )
}

tasks.test {
    useJUnitPlatform()
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
