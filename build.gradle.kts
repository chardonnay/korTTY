plugins {
    id("java")
    id("application")
    id("org.openjfx.javafxplugin") version "0.1.0"
}

group = "de.kortty"
version = "1.0.0-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

repositories {
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
    
    // JediTermFX - Professional terminal emulator for JavaFX (backup)
    implementation("com.techsenger.jeditermfx:jeditermfx-core:1.1.0")
    implementation("com.techsenger.jeditermfx:jeditermfx-ui:1.1.0")
    
    // Lanterna - Text-based terminal emulator with better zoom support
    implementation("com.googlecode.lanterna:lanterna:3.1.2")
    
    // XML Binding (JAXB)
    implementation("jakarta.xml.bind:jakarta.xml.bind-api:4.0.0")
    runtimeOnly("org.glassfish.jaxb:jaxb-runtime:4.0.4")
    
    // ZIP encryption
    implementation("net.lingala.zip4j:zip4j:2.11.5")
    
    // Logging
    implementation("org.slf4j:slf4j-api:2.0.9")
    implementation("ch.qos.logback:logback-classic:1.4.14")
    
    // Testing
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("de.kortty.KorTTYApplication")
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    manifest {
        attributes(
            "Main-Class" to "de.kortty.KorTTYApplication",
            "Implementation-Title" to project.name,
            "Implementation-Version" to project.version
        )
    }
}
