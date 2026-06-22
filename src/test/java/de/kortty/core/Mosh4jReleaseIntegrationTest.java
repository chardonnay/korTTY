package de.kortty.core;

import org.testng.SkipException;
import org.testng.annotations.Test;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

/**
 * Verifies that the pinned mosh4j release ({@code 2.0.2}) stays in sync with the reflection
 * contract that {@link Mosh4jTtyConnector} depends on.
 *
 * <p>The constant checks are hermetic. The reflection-contract check loads the real release JARs
 * (from {@code KORTTY_MOSH4J_RELEASE_DIR} or {@code ~/.kortty/mosh4j}) and self-skips when they are
 * not available locally (e.g. on a CI runner without the artifacts), mirroring how the upstream
 * mosh4j interop test skips when {@code mosh-server} is absent.
 */
public class Mosh4jReleaseIntegrationTest {

    private static final String EXPECTED_VERSION = "2.0.2";
    private static final String EXPECTED_RELEASE_TAG = "v2.0.2";
    private static final String EXPECTED_BCPROV_VERSION = "1.84";
    private static final String EXPECTED_PROTOBUF_VERSION = "4.35.1";
    private static final String[] MODULES = {"protocol", "crypto", "transport", "terminal", "core"};

    @Test
    void versionAndSecurityConstantsAreInSync() throws Exception {
        // Public accessor used by the UI for the "release unavailable" message.
        assertThat(Mosh4jTtyConnector.getMosh4jVersion()).isEqualTo(EXPECTED_VERSION);

        // The GitHub tag carries a leading "v" since 2.0.1 while the artifact version does not;
        // the two must be tracked separately or gh release download targets the wrong tag.
        assertThat(readStaticString("MOSH4J_RELEASE_TAG")).isEqualTo(EXPECTED_RELEASE_TAG);

        // mosh4j 2.0.2 is a security release; the bundled Bouncy Castle dependency must be 1.84
        // (fixes CVE-2026-5598). Guards against regressing the classpath dependency.
        assertThat(readStaticString("DEP_BCPROV_VERSION")).isEqualTo(EXPECTED_BCPROV_VERSION);
        assertThat(readStaticString("DEP_BCPROV_URL")).contains("/" + EXPECTED_BCPROV_VERSION + "/");

        // mosh4j 2.0.2's generated protobuf DTOs require protobuf-java 4.35.1 (they reference
        // com.google.protobuf.GeneratedFile, absent in 4.28.2). Guards against the classpath
        // regression that caused a runtime NoClassDefFoundError on the first Mosh connect.
        assertThat(readStaticString("DEP_PROTOBUF_VERSION")).isEqualTo(EXPECTED_PROTOBUF_VERSION);
        assertThat(readStaticString("DEP_PROTOBUF_URL")).contains("/" + EXPECTED_PROTOBUF_VERSION + "/");
    }

    @Test
    void releaseArtifactsExposeReflectionContract() throws Exception {
        String arch = mapArchSuffix(System.getProperty("os.arch"));
        Path releaseDir = resolveReleaseBaseDir().resolve("release-" + EXPECTED_VERSION + "-" + arch);
        Path depsDir = resolveReleaseBaseDir().resolve("deps");

        // The full runtime classpath the connector builds: the five module jars plus the shared
        // Bouncy Castle and protobuf dependencies. All are required to faithfully link the classes,
        // so a missing artifact self-skips rather than failing for the wrong reason.
        List<Path> classpath = new ArrayList<>();
        Path protocolJar = null;
        for (String module : MODULES) {
            Path jar = releaseDir.resolve("mosh4j-" + module + "-" + EXPECTED_VERSION + "-" + arch + ".jar");
            requireOrSkip(jar);
            classpath.add(jar);
            if ("protocol".equals(module)) {
                protocolJar = jar;
            }
        }
        Path bcprovJar = depsDir.resolve("bcprov-jdk18on-" + EXPECTED_BCPROV_VERSION + ".jar");
        Path protobufJar = depsDir.resolve("protobuf-java-" + EXPECTED_PROTOBUF_VERSION + ".jar");
        requireOrSkip(bcprovJar);
        requireOrSkip(protobufJar);
        classpath.add(bcprovJar);
        classpath.add(protobufJar);

        URL[] urls = new URL[classpath.size()];
        for (int i = 0; i < classpath.size(); i++) {
            urls[i] = classpath.get(i).toUri().toURL();
        }

        try (URLClassLoader loader = new URLClassLoader(urls, getClass().getClassLoader())) {
            Class<?> moshKey = loader.loadClass("org.mosh4j.crypto.MoshKey");
            // Static factory used to decode the Base64 key from the MOSH CONNECT line.
            assertThat(moshKey.getMethod("fromBase64", String.class)).isNotNull();

            Class<?> session = loader.loadClass("org.mosh4j.core.MoshClientSession");
            // Constructor signature the connector instantiates reflectively.
            assertThat(session.getConstructor(InetSocketAddress.class, moshKey, int.class, int.class)).isNotNull();

            Class<?> frontend = loader.loadClass("org.mosh4j.core.MoshTerminalFrontend");
            assertThat(frontend.getConstructor(session)).isNotNull();
            // The full method set the output-drain / input / resize loops invoke via reflection.
            assertThat(frontend.getMethod("sendUserInput", byte[].class)).isNotNull();
            assertThat(frontend.getMethod("sendResize", int.class, int.class)).isNotNull();
            assertThat(frontend.getMethod("takeRenderedOutput", long.class)).isNotNull();
            assertThat(frontend.getMethod("takeHostBytes", long.class)).isNotNull();
            assertThat(frontend.getMethod("sendInitialWakeUp")).isNotNull();
            assertThat(frontend.getMethod("sendHeartbeat")).isNotNull();
            assertThat(frontend.getMethod("start")).isNotNull();
            assertThat(frontend.getMethod("close")).isNotNull();
            assertThat(((Class<?>) frontend.getMethod("isRunning").getReturnType())).isEqualTo(boolean.class);

            // Linkage check: the connector's getMethod(...) calls above only resolve signature types,
            // NOT the generated protobuf DTOs that MoshClientSession's constructor loads at runtime.
            // Loading every top-level generated class forces resolution of its protobuf superclass
            // (e.g. com.google.protobuf.GeneratedFile), so a wrong bundled protobuf version fails here
            // exactly as it would on a real Mosh connect — the gap the original test missed.
            List<String> generated = topLevelClassNames(protocolJar);
            assertThat(generated).isNotEmpty();
            for (String className : generated) {
                try {
                    Class.forName(className, true, loader);
                } catch (Throwable t) {
                    throw new AssertionError("Bundled dependencies do not satisfy generated protobuf DTO '"
                            + className + "'. Expected protobuf-java " + EXPECTED_PROTOBUF_VERSION
                            + " / bcprov " + EXPECTED_BCPROV_VERSION + ". Root cause: " + t, t);
                }
            }
        }
    }

    private static void requireOrSkip(Path jar) {
        if (!Files.isRegularFile(jar)) {
            throw new SkipException("Required mosh4j " + EXPECTED_VERSION + " artifact not found: " + jar
                    + " (run: gh release download " + EXPECTED_RELEASE_TAG + " -R chardonnay/mosh4j, "
                    + "and ensure bcprov/protobuf are cached). Skipping reflection-contract check.");
        }
    }

    /** Top-level (non-inner) class names contained in a jar, in jar order. */
    private static List<String> topLevelClassNames(Path jar) throws Exception {
        List<String> names = new ArrayList<>();
        try (java.util.jar.JarFile jf = new java.util.jar.JarFile(jar.toFile())) {
            for (java.util.jar.JarEntry e : java.util.Collections.list(jf.entries())) {
                String n = e.getName();
                if (n.endsWith(".class") && !n.contains("$") && !n.equals("module-info.class")) {
                    names.add(n.substring(0, n.length() - ".class".length()).replace('/', '.'));
                }
            }
        }
        return names;
    }

    /** Mirrors {@link Mosh4jTtyConnector} arch mapping. */
    private static String mapArchSuffix(String osArchRaw) {
        String osArch = osArchRaw == null ? "" : osArchRaw.toLowerCase();
        if (osArch.contains("aarch64") || osArch.contains("arm64")) {
            return "arm64";
        }
        return "amd64";
    }

    /** Mirrors the env/default precedence the connector uses (bundled layout is not exercised in tests). */
    private static Path resolveReleaseBaseDir() {
        String customDir = System.getenv("KORTTY_MOSH4J_RELEASE_DIR");
        if (customDir == null || customDir.isBlank()) {
            customDir = System.getenv("KORTTY_MOSH4J_SNAPSHOT_DIR");
        }
        if (customDir != null && !customDir.isBlank()) {
            return Path.of(customDir.trim());
        }
        return Path.of(System.getProperty("user.home"), ".kortty", "mosh4j");
    }

    private static String readStaticString(String fieldName) throws Exception {
        Field field = Mosh4jTtyConnector.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (String) field.get(null);
    }
}
