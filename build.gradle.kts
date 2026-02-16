plugins {
    id("java")
    id("application")
    id("org.openjfx.javafxplugin") version "0.1.0"
}

import org.gradle.jvm.toolchain.JvmVendorSpec

group = "de.kortty"
version = "1.7.1"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
        // Explicitly avoid IBM_SEMERU which is not supported in Gradle 9.2.1
        vendor.set(JvmVendorSpec.ADOPTIUM)
    }
}

repositories {
    mavenLocal()  // For local JediTermFX SNAPSHOT builds
    mavenCentral()
    // JetBrains repository for pty4j and its dependencies
    maven {
        url = uri("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies")
    }
}

// JediTermFX is integrated as a git submodule (vendor/jeditermfx).
// The build will auto-init the submodule and install SNAPSHOTs into mavenLocal().
val jeditermfxDir = rootProject.file("vendor/jeditermfx")
val skipJeditermfxSubmodule =
    (findProperty("skipJeditermfxSubmodule") as String?)?.toBoolean() == true ||
    (System.getenv("KORTTY_SKIP_SUBMODULE")?.equals("true", ignoreCase = true) == true)

tasks.register("initJeditermfxSubmodule") {
    description = "Initializes the JediTermFX submodule (if missing). Falls back to git clone if submodule ref is unavailable."
    onlyIf { !skipJeditermfxSubmodule && !jeditermfxDir.resolve("pom.xml").exists() }
    doLast {
        if (skipJeditermfxSubmodule) {
            logger.lifecycle("Skipping jeditermfx submodule init due to KORTTY_SKIP_SUBMODULE=true")
            return@doLast
        }
        fun runCmd(vararg cmd: String): Int {
            val pb = ProcessBuilder(*cmd)
            pb.directory(rootProject.projectDir)
            pb.redirectErrorStream(true)
            val process = pb.start()
            process.inputStream.copyTo(System.out)
            return process.waitFor()
        }
        // Try normal submodule update first
        val exitCode = runCmd("git", "submodule", "update", "--init", "--recursive", "vendor/jeditermfx")
        if (exitCode != 0) {
            logger.warn("Submodule update failed (exit {}), falling back to git clone", exitCode)
            if (jeditermfxDir.exists()) {
                project.delete(jeditermfxDir)
            }
            runCmd("git", "clone", "--depth", "1", "https://github.com/techsenger/jeditermfx.git", "vendor/jeditermfx")
        }
    }
}

tasks.register<Exec>("installJeditermfxLocal") {
    description = "Builds and installs local JediTermFX SNAPSHOT into mavenLocal()"
    workingDir = jeditermfxDir
    val mvnCmd = if (System.getProperty("os.name").lowercase().contains("windows")) "mvn.cmd" else "mvn"
    commandLine(mvnCmd, "-q", "-DskipTests", "install")
    dependsOn("initJeditermfxSubmodule")
    onlyIf { jeditermfxDir.resolve("pom.xml").exists() }
}

tasks.named("compileJava") {
    dependsOn("installJeditermfxLocal")
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
    
    // JediTermFX - Professional terminal emulator for JavaFX
    // Using local SNAPSHOT for testing font zoom and split features
    implementation("com.techsenger.jeditermfx:jeditermfx-core:1.2.0-SNAPSHOT")
    implementation("com.techsenger.jeditermfx:jeditermfx-ui:1.2.0-SNAPSHOT")
    
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
    
    // Logging
    implementation("org.slf4j:slf4j-api:2.0.9")
    implementation("ch.qos.logback:logback-classic:1.4.14")
    
    // Testing
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.1")
}

application {
    mainClass.set("de.kortty.KorTTYApplication")
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

// Task zum Vorbereiten der jpackage Eingabe
tasks.register("prepareJpackage") {
    dependsOn("copyDependencies", "copyJar")
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
        "--java-options", "-Djava.awt.headless=false"
    )
    return args
}

// ==================== macOS ====================
if (isMac) {
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
        "--enable-native-access=javafx.graphics",
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
    dependsOn("installJeditermfxLocal")
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
