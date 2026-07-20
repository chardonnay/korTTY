package de.kortty.core;

import org.testng.SkipException;
import org.testng.annotations.Test;

import java.nio.file.Path;
import java.util.Locale;

import static com.google.common.truth.Truth.assertThat;

class LocalProcessDirectoryTest {

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    // ---- Pure parser: lsof -Fn field output --------------------------------------------------

    @Test
    void parsesCwdPathFromLsofFieldOutput() {
        String output = "p4321\n" + "fcwd\n" + "n/Users/dan/Software Projects/korTTY\n";
        assertThat(LocalProcessDirectory.parseLsofCwdOutput(output))
            .isEqualTo("/Users/dan/Software Projects/korTTY");
    }

    @Test
    void returnsFirstNameFieldWhenSeveralArePresent() {
        String output = "p1\nfcwd\n/first/is/ignored-no-prefix\nn/real/cwd\nn/later/one\n";
        assertThat(LocalProcessDirectory.parseLsofCwdOutput(output)).isEqualTo("/real/cwd");
    }

    @Test
    void returnsNullWhenNoNameFieldPresent() {
        assertThat(LocalProcessDirectory.parseLsofCwdOutput("p1\nfcwd\n")).isNull();
    }

    @Test
    void returnsNullForBlankOrNullOrEmptyNameField() {
        assertThat(LocalProcessDirectory.parseLsofCwdOutput(null)).isNull();
        assertThat(LocalProcessDirectory.parseLsofCwdOutput("")).isNull();
        assertThat(LocalProcessDirectory.parseLsofCwdOutput("   ")).isNull();
        // A bare "n" with no path must not be mistaken for a valid (empty) directory.
        assertThat(LocalProcessDirectory.parseLsofCwdOutput("p1\nn\n")).isNull();
    }

    @Test
    void doesNotConfuseOtherFieldsWithTheNameField() {
        // 'p' (pid) and 'f' (fd) lines also start with letters; only 'n' is the name field.
        assertThat(LocalProcessDirectory.parseLsofCwdOutput("p99999\nfcwd\n")).isNull();
    }

    @Test
    void decodesLsofCStyleEscapesInTheNameField() {
        // lsof escapes a real newline/tab in a directory name as the literal two chars \n / \t, so
        // the whole path stays on one physical line. Decoding must restore the real control byte.
        assertThat(LocalProcessDirectory.parseLsofCwdOutput("p1\nfcwd\nn/tmp/has\\nnewline\n"))
            .isEqualTo("/tmp/has\nnewline");
        assertThat(LocalProcessDirectory.parseLsofCwdOutput("p1\nfcwd\nn/tmp/has\\ttab\n"))
            .isEqualTo("/tmp/has\ttab");
        // A real backslash in the name arrives doubled; \xHH is a hex byte escape.
        assertThat(LocalProcessDirectory.parseLsofCwdOutput("p1\nfcwd\nn/tmp/a\\\\b\n"))
            .isEqualTo("/tmp/a\\b");
        assertThat(LocalProcessDirectory.parseLsofCwdOutput("p1\nfcwd\nn/tmp/sp\\x20ace\n"))
            .isEqualTo("/tmp/sp ace");
    }

    @Test
    void reassemblesMultiByteUtf8FromConsecutiveHexEscapes() {
        // Under a non-UTF-8 locale (unset LANG) lsof escapes every non-ASCII byte, so "ü" arrives as
        // the two escapes \xc3\xbc. They are ONE character: decoding each byte to its own char would
        // yield "Ã¼" and the resulting path would no longer exist on disk.
        assertThat(LocalProcessDirectory.parseLsofCwdOutput("p1\nfcwd\nn/tmp/Ziel \\xc3\\xbc\n"))
            .isEqualTo("/tmp/Ziel ü");
        // Four-byte sequences (outside the BMP) must survive too.
        assertThat(LocalProcessDirectory.parseLsofCwdOutput("p1\nfcwd\nn/tmp/\\xf0\\x9f\\x93\\x81\n"))
            .isEqualTo("/tmp/📁");
        // Under a UTF-8 locale the same byte is printed literally and must pass through unchanged,
        // even when another part of the name forces the escape path.
        assertThat(LocalProcessDirectory.parseLsofCwdOutput("p1\nfcwd\nn/tmp/Ziel ü\\x20x\n"))
            .isEqualTo("/tmp/Ziel ü x");
        assertThat(LocalProcessDirectory.parseLsofCwdOutput("p1\nfcwd\nn/tmp/📁\\x20x\n"))
            .isEqualTo("/tmp/📁 x");
    }

    @Test
    void leavesOrdinaryPathsUntouched() {
        // No backslash -> returned verbatim (the common case).
        assertThat(LocalProcessDirectory.parseLsofCwdOutput("p1\nfcwd\nn/Users/dan/projects/korTTY\n"))
            .isEqualTo("/Users/dan/projects/korTTY");
    }

    // ---- End-to-end OS query: read this JVM's own cwd ----------------------------------------

    @Test
    void readsOwnProcessWorkingDirectoryOnPosix() throws Exception {
        if (isWindows()) {
            throw new SkipException("cwd lookup is unsupported on Windows (returns null by design)");
        }
        long ownPid = ProcessHandle.current().pid();
        String reported = LocalProcessDirectory.read(ownPid);
        assertThat(reported).isNotNull();
        // Compare canonicalized paths: lsof/proc report the real path, which may differ from
        // user.dir only by symlink resolution (e.g. /tmp -> /private/tmp on macOS).
        assertThat(Path.of(reported).toRealPath())
            .isEqualTo(Path.of(System.getProperty("user.dir")).toRealPath());
    }

    @Test
    void returnsNullForNonPositivePid() {
        assertThat(LocalProcessDirectory.read(0)).isNull();
        assertThat(LocalProcessDirectory.read(-1)).isNull();
    }
}
