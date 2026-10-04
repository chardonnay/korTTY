package de.kortty.core.remote.edit;

import org.testng.SkipException;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.assertThrows;

/**
 * The shell commands of an edit as root ({@link SudoEditCommands}): hostile paths stay one literal
 * word, no command carries a secret, and the write stage is root's own, verified and always removed.
 */
public class SudoEditCommandBuilderTest {

    private static final String SHA = "a".repeat(64);
    private static final List<String> HOSTILE = List.of(
        "/etc/it's here.conf",
        "/etc/new\nline.conf",
        "/etc/-rf",
        "/etc/$(touch pwned)`id`.conf",
        "/srv/with space/\"quoted\" file");

    @Test
    public void everyCommandRefusesRelativePathsAndNul() {
        for (String bad : List.of("etc/passwd", "-rf", "~/x", "")) {
            assertThrows(IllegalArgumentException.class, () -> SudoEditCommands.read(bad));
            assertThrows(IllegalArgumentException.class, () -> SudoEditCommands.inspect(bad));
            assertThrows(IllegalArgumentException.class, () -> SudoEditCommands.write(bad, 1, SHA));
            assertThrows(IllegalArgumentException.class, () -> SudoEditCommands.userWritableFolder(bad));
        }
        assertThrows(IllegalArgumentException.class, () -> SudoEditCommands.read("/etc/a\0b"));
    }

    @Test
    public void writeAcceptsOnlyALowercaseShaAndASize() {
        assertThrows(IllegalArgumentException.class, () -> SudoEditCommands.write("/etc/x", 1, "abc"));
        assertThrows(IllegalArgumentException.class, () -> SudoEditCommands.write("/etc/x", 1, "A".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> SudoEditCommands.write("/etc/x", 1, SHA + "; rm -rf /"));
        assertThrows(IllegalArgumentException.class, () -> SudoEditCommands.write("/etc/x", -1, SHA));
    }

    @Test
    public void theStageIsRootsOwnVerifiedAndRemovedOnEveryExit() {
        String write = SudoEditCommands.write("/etc/app.conf", 42, SHA);

        // Created by root inside the sudo command, never handed to the login user.
        assertThat(write).startsWith("umask 077; d=$(mktemp -d) || exit 70; ");
        assertThat(write).doesNotContain("chown");
        assertThat(write).doesNotContain("chmod");
        // The trap is the very next step, so no later exit can leave the stage behind.
        assertThat(write).contains("d=$(mktemp -d) || exit 70; trap 'rm -rf -- \"$d\"' EXIT; trap 'exit 129' HUP INT TERM PIPE; ");
        // Size, then hash, then the link check, then the in-place write; in that order.
        int size = write.indexOf("[ \"$n\" = 42 ] || exit 72");
        int hash = write.indexOf("!= " + SHA + " ]; then exit 73");
        int link = write.indexOf("if [ -L '/etc/app.conf' ] || [ ! -f '/etc/app.conf' ]; then exit 3; fi");
        int copy = write.indexOf("cat -- \"$f\" > '/etc/app.conf' || exit 75");
        assertThat(size).isGreaterThan(0);
        assertThat(hash).isGreaterThan(size);
        assertThat(link).isGreaterThan(hash);
        assertThat(copy).isGreaterThan(link);
        // In place: no mv, no install, no rm of the target.
        assertThat(write).doesNotContain("mv ");
        assertThat(write).doesNotContain("install ");
        assertThat(write).doesNotContain("rm -f");
    }

    @Test
    public void readStagesNothingAndRefusesLinks() {
        String read = SudoEditCommands.read("/etc/app.conf");

        assertThat(read).startsWith("if [ -L '/etc/app.conf' ] || [ ! -f '/etc/app.conf' ]; then exit 3; fi; ");
        assertThat(read).doesNotContain("mktemp");
        assertThat(read).endsWith("exec cat -- '/etc/app.conf'");
        assertThat(read).contains("stat -c '%u %g %a' -- '/etc/app.conf'");
        assertThat(read).contains("stat -f '%u %g %Lp' -- '/etc/app.conf'");
    }

    @Test
    public void hostilePathsStayOneLiteralWord() throws Exception {
        for (String path : HOSTILE) {
            String quoted = de.kortty.core.remote.RemoteShell.quote(path);
            for (String command : List.of(SudoEditCommands.read(path), SudoEditCommands.inspect(path),
                    SudoEditCommands.write(path, 1, SHA))) {
                assertThat(command).contains(quoted);
                assertThat(command).doesNotContain("-- " + path + " ");
            }
            assertThat(SudoEditCommands.userWritableFolder(path))
                .contains(de.kortty.core.remote.RemoteShell.quote(SudoEditCommands.parentOf(path)));
        }
        assertRoundTripThroughARealShell();
    }

    /** The quoted path reaches a real {@code sh} unchanged, and nothing in it runs. */
    private static void assertRoundTripThroughARealShell() throws Exception {
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            throw new SkipException("needs /bin/sh");
        }
        Path dir = Files.createTempDirectory("kortty-sudo-edit-quote-");
        try {
            for (String path : HOSTILE) {
                String command = "printf '%s' " + de.kortty.core.remote.RemoteShell.quote(path);
                Process process = new ProcessBuilder("/bin/sh", "-c", command).directory(dir.toFile()).start();
                assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
                assertThat(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8)).isEqualTo(path);
            }
            assertThat(Files.exists(dir.resolve("pwned"))).isFalse();
        } finally {
            deleteTree(dir);
        }
    }

    @Test
    public void noCommandCarriesASecretOrASudoInvocation() {
        String secret = "S3cret pw'\"$x";
        for (String command : List.of(SudoEditCommands.read("/etc/x"), SudoEditCommands.inspect("/etc/x"),
                SudoEditCommands.write("/etc/x", 3, SHA), SudoEditCommands.userWritableFolder("/etc/x"))) {
            assertThat(command).doesNotContain(secret);
            // The sudo wrapper is added by SudoCommand; these are the inner commands only.
            assertThat(command).doesNotContain("sudo");
            assertThat(command).doesNotContain("-S");
        }
    }

    @Test
    public void everyFolderOnTheWayIsCheckedForWriteAccessAndGuardedAgainstLinks() {
        String check = SudoEditCommands.userWritableFolder("/srv/app/conf/site.conf");
        assertThat(check).contains("for d in '/srv/app/conf' '/srv/app' '/srv' '/'; do");
        assertThat(SudoEditCommands.userWritableFolder("/x.conf")).contains("for d in '/'; do");
        // Right before root reads or writes, the folder must still be the physical one it was.
        for (String command : List.of(SudoEditCommands.read("/srv/app/x"), SudoEditCommands.write("/srv/app/x", 1, SHA))) {
            assertThat(command).contains("\"$(cd -P '/srv/app' 2>/dev/null && pwd -P)\" != '/srv/app'");
        }
        String write = SudoEditCommands.write("/srv/app/x", 1, SHA);
        assertThat(write.indexOf("pwd -P")).isLessThan(write.indexOf("cat -- \"$f\" > '/srv/app/x'"));
    }

    @Test
    public void parentOfAbsolutePaths() {
        assertThat(SudoEditCommands.parentOf("/etc/app.conf")).isEqualTo("/etc");
        assertThat(SudoEditCommands.parentOf("/app.conf")).isEqualTo("/");
        assertThat(SudoEditCommands.parentOf("/srv/a/b/")).isEqualTo("/srv/a");
    }

    @Test
    public void inspectAnswersAreParsedStrictly() throws IOException {
        assertThat(SudoEditService.parseInspect("/etc/x", "file\n/etc\n").kind()).isEqualTo(SudoEditService.TargetKind.FILE);
        assertThat(SudoEditService.parseInspect("/x", "file\n/\n").kind()).isEqualTo(SudoEditService.TargetKind.FILE);
        // A folder on the way is a link: the file really lives elsewhere and is confirmed like a link.
        SudoEditService.Target viaLinkedFolder = SudoEditService.parseInspect("/etc/x", "file\n/private/etc\n");
        assertThat(viaLinkedFolder.kind()).isEqualTo(SudoEditService.TargetKind.LINK);
        assertThat(viaLinkedFolder.resolvedPath()).isEqualTo("/private/etc/x");
        assertThrows(IOException.class, () -> SudoEditService.parseInspect("/etc/x", "file\n"));
        assertThat(SudoEditService.parseInspect("/etc/x", "missing\n").kind())
            .isEqualTo(SudoEditService.TargetKind.MISSING);
        SudoEditService.Target link = SudoEditService.parseInspect("/etc/x", "link\n/srv/real\nname\n");
        assertThat(link.kind()).isEqualTo(SudoEditService.TargetKind.LINK);
        assertThat(link.resolvedPath()).isEqualTo("/srv/real\nname");
        assertThrows(IOException.class, () -> SudoEditService.parseInspect("/etc/x", "link\nrelative\n"));
        assertThrows(IOException.class, () -> SudoEditService.parseInspect("/etc/x", "Password:\n"));
    }

    private static void deleteTree(Path dir) throws IOException {
        try (var paths = Files.walk(dir)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }
}
