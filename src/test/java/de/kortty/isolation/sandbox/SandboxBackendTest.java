package de.kortty.isolation.sandbox;

import static com.google.common.truth.Truth.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.testng.SkipException;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

/**
 * The sandbox backends: the generated macOS profile and bubblewrap command line, and, on a computer whose
 * sandbox works, that a sandboxed process really cannot read a hidden folder while it still runs and can
 * write where it is allowed to.
 */
class SandboxBackendTest {

    private Path tempDir;

    @AfterMethod
    void cleanup() throws IOException {
        if (tempDir == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(tempDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
        tempDir = null;
    }

    private Path dir() throws IOException {
        if (tempDir == null) {
            tempDir = Files.createTempDirectory("kt-sandbox");
        }
        return tempDir;
    }

    /** The macOS profile names POSIX paths; on Windows they would turn into drive paths. */
    private static void requirePosixPaths() {
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            throw new SkipException("the sandbox profiles name POSIX paths; Windows has no sandbox backend");
        }
    }

    @Test
    void theMacProfileHidesThenGivesBackInOrder() {
        requirePosixPaths();
        SandboxSpec spec = new SandboxSpec(List.of(Path.of("/Users/u/.kortty")),
            List.of(Path.of("/Users/u/.kortty/shell-integration/tab-1")), true, List.of(Path.of("/work")));
        String profile = MacSandboxBackend.profile(spec);
        int denyWrite = profile.indexOf("(deny file-write*)");
        int allowWrite = profile.indexOf("(allow file-write* (subpath \"/dev\") (subpath \"/work\")");
        int hide = profile.indexOf("(deny file-read* file-write* (subpath \"/Users/u/.kortty\")");
        int giveBack = profile.indexOf("(allow file-read* (subpath \"/Users/u/.kortty/shell-integration/tab-1\")");
        assertThat(denyWrite).isAtLeast(0);
        assertThat(allowWrite).isGreaterThan(denyWrite);
        assertThat(hide).isGreaterThan(allowWrite);
        assertThat(giveBack).isGreaterThan(hide);
    }

    @Test
    void aPortListLimitsOutboundConnectionsToThoseAndThisComputer() {
        String profile = MacSandboxBackend.profile(new SandboxSpec(List.of(), List.of(), false, List.of(),
            List.of(22, 2222, 0, 70000)));
        assertThat(profile).contains("(deny network-outbound)");
        assertThat(profile).contains("(remote ip \"localhost:*\")");
        assertThat(profile).contains("(remote tcp \"*:22\")");
        assertThat(profile).contains("(remote tcp \"*:2222\")");
        assertThat(profile).doesNotContain("*:0\"");
        assertThat(profile).doesNotContain("*:70000");
        assertThat(profile).contains("mDNSResponder");
        assertThat(MacSandboxBackend.profile(new SandboxSpec(List.of(), List.of(), false, List.of())))
            .doesNotContain("network-outbound");
    }

    @Test
    void theMacProfileQuotesPaths() {
        assertThat(MacSandboxBackend.quote("/a \"b\"\\c")).isEqualTo("\"/a \\\"b\\\"\\\\c\"");
    }

    @Test
    void anUnrestrictedProfileDoesNotTakeAwayWriting() {
        requirePosixPaths();
        String profile = MacSandboxBackend.profile(new SandboxSpec(List.of(Path.of("/secret")), List.of(), false,
            List.of()));
        assertThat(profile).doesNotContain("(deny file-write*)");
        assertThat(profile).contains("(deny file-read* file-write* (subpath \"/secret\")");
    }

    @Test
    void bubblewrapMountsReadOnlyHidesAndGivesBack() throws IOException {
        Path hidden = Files.createDirectories(dir().resolve("cfg"));
        Path inner = Files.createDirectories(hidden.resolve("inner"));
        Path work = Files.createDirectories(dir().resolve("work"));
        List<String> command = new BubblewrapSandboxBackend().wrap(List.of("/bin/sh", "-c", "true"),
            new SandboxSpec(List.of(hidden), List.of(inner), true, List.of(work)));
        String line = String.join(" ", command);
        assertThat(line).contains("--die-with-parent --unshare-pid --ro-bind / / --dev-bind /dev /dev --proc /proc");
        assertThat(line).contains("--bind " + work + " " + work);
        assertThat(line).contains("--tmpfs " + hidden);
        assertThat(line).contains("--ro-bind " + inner + " " + inner);
        assertThat(line.indexOf("--tmpfs " + hidden)).isLessThan(line.indexOf("--ro-bind " + inner));
        assertThat(command.subList(command.size() - 4, command.size()))
            .containsExactly("--", "/bin/sh", "-c", "true").inOrder();
    }

    @Test
    void theSelfTestPassesWhereASandboxWorks() throws IOException {
        SandboxBackend backend = SandboxSupport.backend();
        if (backend == null || !backend.installed()) {
            throw new SkipException("No sandbox backend on this computer");
        }
        SandboxSupport.Availability availability = SandboxSupport.check(backend, dir().resolve("config"));
        if (!availability.available() && System.getProperty("os.name", "").toLowerCase().contains("linux")) {
            // bubblewrap needs unprivileged user namespaces, which containers usually take away.
            throw new SkipException("bubblewrap cannot run here: " + availability.detail());
        }
        assertThat(availability.status()).isEqualTo(SandboxSupport.Status.AVAILABLE);
    }

    @Test
    void aSandboxedProcessCannotReadAHiddenFolderButWritesWhereAllowed() throws Exception {
        SandboxBackend backend = SandboxSupport.backend();
        if (backend == null || !backend.installed()
                || !SandboxSupport.check(backend, dir().resolve("probe-config")).available()) {
            throw new SkipException("No working sandbox on this computer");
        }
        Path hidden = Files.createDirectories(dir().resolve("vault"));
        Files.writeString(hidden.resolve("secret"), "top-secret");
        Path readable = Files.createDirectories(hidden.resolve("allowed"));
        Files.writeString(readable.resolve("rc"), "allowed-content");
        Path work = Files.createDirectories(dir().resolve("work"));
        Path outside = dir().resolve("outside.txt");
        SandboxSpec spec = new SandboxSpec(List.of(hidden), List.of(readable), true, List.of(work));
        String script = "cat '" + hidden.resolve("secret") + "' 2>/dev/null; "
            + "cat '" + readable.resolve("rc") + "'; echo; "
            + "echo written > '" + work.resolve("ok.txt") + "'; "
            + "echo leaked > '" + outside + "' 2>/dev/null; echo done";
        String output = run(backend.wrap(List.of("/bin/sh", "-c", script), spec));
        assertThat(output).doesNotContain("top-secret");
        assertThat(output).contains("allowed-content");
        assertThat(output).contains("done");
        assertThat(Files.readString(work.resolve("ok.txt")).trim()).isEqualTo("written");
        assertThat(Files.exists(outside)).isFalse();
    }

    private static String run(List<String> command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        process.getOutputStream().close();
        byte[] out;
        try (InputStream in = process.getInputStream()) {
            out = in.readAllBytes();
        }
        assertThat(process.waitFor(20, TimeUnit.SECONDS)).isTrue();
        return new String(out, StandardCharsets.UTF_8);
    }
}
