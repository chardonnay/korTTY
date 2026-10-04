package de.kortty.core.remote;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import de.kortty.core.remote.edit.RemoteEditHashes;
import de.kortty.core.remote.edit.RemoteEditSession;
import de.kortty.core.remote.edit.SudoEditCommands;
import de.kortty.core.remote.edit.SudoEditService;
import de.kortty.core.remote.edit.SudoEditSession;
import de.kortty.ui.sftp.SudoEditJournal;
import org.apache.sshd.client.session.ClientSession;
import org.slf4j.LoggerFactory;
import org.testng.SkipException;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.assertThrows;

/**
 * An edit as root end to end (SFTP-22, D14) against a loopback SSH server whose exec channels run
 * real shells, with the scripted {@code sudo} of {@link RemoteCommandRunnerIntegrationTest} first
 * on PATH. The fake sudo runs commands as the test user, so "root" here is the test user; what is
 * checked is the protocol: the password goes only through the nonce handshake, the stage is
 * created inside the command (in {@code TMPDIR}, which the test points at its own folder) and
 * always removed, the target is written in place only after the size and hash checks, and no
 * secret shows up anywhere. Unix only.
 */
public class SudoEditIntegrationTest {

    private static final String SECRET = "Edit pw'\"$x ä";
    private static final String ORIGINAL = "listen 80;\nserver_name example.test;\n";

    private Path dir;
    private Path etc;
    private Path stageRoot;
    private Path editRoot;
    private ExecLoopbackServer server;
    private ClientSession session;
    private RemoteCommandRunner runner;
    private Logger root;
    private Logger koLogger;
    private Level previousLevel;
    private ListAppender<ILoggingEvent> appender;
    private final List<String> errorTexts = new ArrayList<>();

    @BeforeClass
    public void startServer() throws IOException {
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            throw new SkipException("exec fixture runs /bin/sh");
        }
        dir = Files.createTempDirectory("kortty-sudo-edit-").toRealPath();
        Path bin = Files.createDirectories(dir.resolve("bin"));
        Path sudo = bin.resolve("sudo");
        Files.writeString(sudo, RemoteCommandRunnerIntegrationTest.FAKE_SUDO.replace("\r\n", "\n"));
        Files.setPosixFilePermissions(sudo, PosixFilePermissions.fromString("rwxr-xr-x"));
        stageRoot = Files.createDirectories(dir.resolve("server-tmp"));
        server = new ExecLoopbackServer(dir.resolve("host.ser"), bin);
        server.environment().put("FAKE_SUDO_PASSWORD", SECRET);
        server.environment().put("TMPDIR", stageRoot.toString());
        session = server.connect();
        runner = new RemoteCommandRunner(() -> session);
    }

    @AfterClass(alwaysRun = true)
    public void stopServer() throws IOException {
        if (session != null) {
            session.close(true);
        }
        if (server != null) {
            server.close();
        }
        if (dir != null) {
            try (Stream<Path> paths = Files.walk(dir)) {
                paths.filter(Files::isDirectory).forEach(path -> path.toFile().setWritable(true, true));
            }
            try (Stream<Path> paths = Files.walk(dir)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }

    @BeforeMethod
    public void prepare() throws IOException {
        server.environment().put("FAKE_SUDO_MODE", "password");
        String name = "etc-" + System.nanoTime();
        etc = Files.createDirectories(dir.resolve(name));
        editRoot = Files.createDirectories(dir.resolve("local-" + name));
        root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        koLogger = (Logger) LoggerFactory.getLogger("de.kortty");
        previousLevel = koLogger.getLevel();
        koLogger.setLevel(Level.DEBUG);
        appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        errorTexts.clear();
    }

    /** No password, in any form, in a log line, an exception text or a journal note. */
    @AfterMethod(alwaysRun = true)
    public void assertNoSecretLeaked() throws IOException {
        root.detachAppender(appender);
        koLogger.setLevel(previousLevel);
        List<String> texts = new ArrayList<>(errorTexts);
        for (ILoggingEvent event : appender.list) {
            texts.add(event.getFormattedMessage());
            for (IThrowableProxy proxy = event.getThrowableProxy(); proxy != null; proxy = proxy.getCause()) {
                texts.add(String.valueOf(proxy.getMessage()));
            }
        }
        for (String text : texts) {
            assertThat(text).doesNotContain(SECRET);
            assertThat(text).doesNotContain("KORTTYP");
        }
        // Every stage the commands created is gone again.
        try (Stream<Path> left = Files.list(stageRoot)) {
            assertThat(left.toList()).isEmpty();
        }
        etc.toFile().setWritable(true, true);
    }

    /** A root-ish config file: mode 0640 in a folder the login user cannot write. */
    private Path targetFile(String name, String content) throws IOException {
        Path file = etc.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r-----"));
        Files.setPosixFilePermissions(etc, PosixFilePermissions.fromString("r-xr-xr-x"));
        return file;
    }

    private SudoEditSession open(Path file, String secret) throws IOException {
        SudoEditService service = new SudoEditService(runner);
        char[] password = secret == null ? null : secret.toCharArray();
        try {
            // The folder check would refuse every path below the test's own temp folder (the test
            // user can write there); it has tests of its own below.
            return service.open(file.toString(), Optional.ofNullable(password), editRoot);
        } catch (IOException e) {
            errorTexts.add(String.valueOf(e.getMessage()));
            errorTexts.add(String.valueOf(e));
            throw e;
        }
    }

    private static Object inode(Path file) throws IOException {
        return Files.getAttribute(file, "unix:ino");
    }

    @Test
    public void readEditAndWriteBackKeepTheModeAndTheInode() throws Exception {
        Path file = targetFile("nginx.conf", ORIGINAL);
        Object inode = inode(file);
        SudoEditService service = new SudoEditService(runner);
        assertThat(service.needsPassword()).isTrue();

        try (SudoEditSession edit = open(file, SECRET)) {
            assertThat(Files.readString(edit.localFile())).isEqualTo(ORIGINAL);
            assertThat(edit.baseline().size()).isEqualTo(ORIGINAL.length());
            assertThat(edit.baseline().statLine()).endsWith(" 640");
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(edit.folder())))
                .isEqualTo("rwx------");

            String edited = ORIGINAL + "gzip on;\n";
            Files.writeString(edit.localFile(), edited);
            assertThat(edit.hasUnsyncedChanges()).isTrue();
            assertThat(edit.upload()).isEqualTo(RemoteEditSession.UploadResult.UPLOADED);

            assertThat(Files.readString(file)).isEqualTo(edited);
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-r-----");
            assertThat(inode(file)).isEqualTo(inode);
            assertThat(edit.uploads()).isEqualTo(1);
            assertThat(edit.hasUnsyncedChanges()).isFalse();
            assertThat(edit.lastUploadHashChecked()).isTrue();
            // Saving the same content again sends nothing.
            assertThat(edit.upload()).isEqualTo(RemoteEditSession.UploadResult.UNCHANGED);
            String journal = SudoEditJournal.note(edit.remotePath());
            assertThat(journal).isEqualTo("sudo-edit " + file);
            errorTexts.add(journal);
            edit.close();
            assertThat(Files.exists(edit.folder())).isFalse();
        }
    }

    @Test
    public void aChangeOnTheServerIsAConflictUntilTheUserOverwrites() throws Exception {
        Path file = targetFile("app.conf", ORIGINAL);
        try (SudoEditSession edit = open(file, SECRET)) {
            Files.writeString(file, "someone else\n");
            Files.writeString(edit.localFile(), "mine\n");

            assertThat(edit.upload()).isEqualTo(RemoteEditSession.UploadResult.CONFLICT);
            assertThat(edit.conflict().kind()).isEqualTo(RemoteEditSession.ConflictKind.CHANGED);
            assertThat(Files.readString(file)).isEqualTo("someone else\n");

            assertThat(edit.forceUpload()).isEqualTo(RemoteEditSession.UploadResult.UPLOADED);
            assertThat(Files.readString(file)).isEqualTo("mine\n");
        }
    }

    @Test
    public void aTruncatedTransferLeavesTheTargetUnchangedAndNoStage() throws Exception {
        Path file = targetFile("truncated.conf", ORIGINAL);
        Object inode = inode(file);
        byte[] full = "a whole new file that never arrives in full\n".getBytes(StandardCharsets.UTF_8);
        String sha = RemoteEditHashes.sha256(full);
        // Half of the bytes, then the local side breaks: the channel's stdin just ends.
        InputStream broken = new InputStream() {
            private final ByteArrayInputStream half = new ByteArrayInputStream(full, 0, full.length / 2);

            @Override
            public int read() throws IOException {
                int value = half.read();
                if (value < 0) {
                    throw new IOException("local disk went away");
                }
                return value;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                int read = half.read(b, off, len);
                if (read < 0) {
                    throw new IOException("local disk went away");
                }
                return read;
            }
        };

        RemoteCommandRunner.Result result = runner.run(
            RemoteCommandRunner.Request.sudo(SudoCommand.wrap(SudoEditCommands.write(file.toString(), full.length, sha)),
                    Optional.of(SECRET.toCharArray()))
                .stdin(broken)
                .timeout(Duration.ofSeconds(30)));

        assertThat(result.exitCode()).isEqualTo(SudoEditCommands.EXIT_SIZE_MISMATCH);
        assertThat(Files.readString(file)).isEqualTo(ORIGINAL);
        assertThat(inode(file)).isEqualTo(inode);
        errorTexts.add(result.stderr());
    }

    @Test
    public void bytesThatArriveAlteredAreRefusedByTheHashCheck() throws Exception {
        Path file = targetFile("hash.conf", ORIGINAL);
        byte[] sent = "same length, other bytes!\n".getBytes(StandardCharsets.UTF_8);
        byte[] claimed = "same length, other BYTES!\n".getBytes(StandardCharsets.UTF_8);

        RemoteCommandRunner.Result result = runner.run(
            RemoteCommandRunner.Request.sudo(SudoCommand.wrap(SudoEditCommands.write(file.toString(), sent.length,
                    RemoteEditHashes.sha256(claimed))), Optional.of(SECRET.toCharArray()))
                .stdin(new ByteArrayInputStream(sent))
                .timeout(Duration.ofSeconds(30)));

        // Servers without sha256sum/shasum fall back to the size check; the test machine has one.
        assertThat(result.exitCode()).isEqualTo(SudoEditCommands.EXIT_HASH_MISMATCH);
        assertThat(Files.readString(file)).isEqualTo(ORIGINAL);
    }

    @Test
    public void cancellingMidWriteLeavesTheTargetUnchanged() throws Exception {
        Path file = targetFile("cancelled.conf", ORIGINAL);
        byte[] full = new byte[256 * 1024];
        java.util.Arrays.fill(full, (byte) 'x');
        RemoteCommandCancellation cancellation = new RemoteCommandCancellation();
        // A stream that sends a little and then stalls until the command is cancelled.
        InputStream stalling = new InputStream() {
            private int sent;

            @Override
            public int read() {
                return -1;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (sent < 1024) {
                    int n = Math.min(len, 1024 - sent);
                    java.util.Arrays.fill(b, off, off + n, (byte) 'x');
                    sent += n;
                    return n;
                }
                cancellation.cancel();
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                throw new IOException("cancelled");
            }
        };

        assertThrows(RemoteCommandCancelledException.class, () -> runner.run(
            RemoteCommandRunner.Request.sudo(SudoCommand.wrap(SudoEditCommands.write(file.toString(), full.length,
                    RemoteEditHashes.sha256(full))), Optional.of(SECRET.toCharArray()))
                .stdin(stalling)
                .cancellation(cancellation)
                .timeout(Duration.ofSeconds(30))));

        assertThat(Files.readString(file)).isEqualTo(ORIGINAL);
        // The loopback fixture kills the process tree on close (a real sshd lets the EXIT trap run),
        // so remove what may be left for the shared stage check.
        waitForStageCleanupOrClear();
    }

    private void waitForStageCleanupOrClear() throws IOException, InterruptedException {
        for (int i = 0; i < 20; i++) {
            try (Stream<Path> left = Files.list(stageRoot)) {
                if (left.findAny().isEmpty()) {
                    return;
                }
            }
            Thread.sleep(100);
        }
        try (Stream<Path> paths = Files.walk(stageRoot)) {
            paths.sorted(Comparator.reverseOrder()).filter(path -> !path.equals(stageRoot))
                .forEach(path -> path.toFile().delete());
        }
    }

    @Test
    public void aWrongPasswordIsACleanErrorAndLeavesNothingBehind() throws Exception {
        Path file = targetFile("secret.conf", ORIGINAL);

        assertThrows(SudoAuthenticationException.class, () -> open(file, "not the password"));

        try (Stream<Path> left = Files.list(editRoot)) {
            assertThat(left.toList()).isEmpty();
        }
        assertThat(Files.readString(file)).isEqualTo(ORIGINAL);
    }

    @Test
    public void withNopasswdNoPasswordIsNeeded() throws Exception {
        server.environment().put("FAKE_SUDO_MODE", "nopasswd");
        Path file = targetFile("nopasswd.conf", ORIGINAL);
        SudoEditService service = new SudoEditService(runner);

        assertThat(service.needsPassword()).isFalse();
        try (SudoEditSession edit = open(file, null)) {
            Files.writeString(edit.localFile(), "changed\n");
            assertThat(edit.upload()).isEqualTo(RemoteEditSession.UploadResult.UPLOADED);
        }
        assertThat(Files.readString(file)).isEqualTo("changed\n");
    }

    @Test
    public void requirettyIsReportedNotWorkedAround() throws Exception {
        server.environment().put("FAKE_SUDO_MODE", "requiretty");
        Path file = targetFile("tty.conf", ORIGINAL);

        assertThrows(SudoRequiresTtyException.class, () -> open(file, SECRET));
    }

    @Test
    public void aLinkIsShownWithItsTargetAndNeverReadThrough() throws Exception {
        Path real = targetFile("real.conf", ORIGINAL);
        etc.toFile().setWritable(true, true);
        Path link = Files.createSymbolicLink(etc.resolve("link.conf"), real.getFileName());
        Files.setPosixFilePermissions(etc, PosixFilePermissions.fromString("r-xr-xr-x"));
        SudoEditService service = new SudoEditService(runner);

        SudoEditService.Target target = service.inspect(link.toString(), Optional.of(SECRET.toCharArray()));

        assertThat(target.kind()).isEqualTo(SudoEditService.TargetKind.LINK);
        assertThat(target.resolvedPath()).isEqualTo(real.toRealPath().toString());
        // Reading the link itself is refused; only the confirmed target is opened.
        IOException refused = expectIo(() -> service.open(link.toString(), Optional.of(SECRET.toCharArray()), editRoot));
        assertThat(refused).hasMessageThat().contains(link.toString());
        try (SudoEditSession edit = service.open(target.resolvedPath(), Optional.of(SECRET.toCharArray()), editRoot)) {
            assertThat(Files.readString(edit.localFile())).isEqualTo(ORIGINAL);
        }
        assertThat(service.inspect(etc.resolve("none").toString(), Optional.of(SECRET.toCharArray())).kind())
            .isEqualTo(SudoEditService.TargetKind.MISSING);
    }

    @Test
    public void aFileInAFolderTheUserCanWriteIsRefused() throws Exception {
        Path file = etc.resolve("writable.conf");
        Files.writeString(file, ORIGINAL);

        assertThrows(SudoEditService.UserWritableFolderException.class,
            () -> new SudoEditService(runner).refuseUserWritableFolder(file.toString()));
    }

    @Test
    public void aFileBelowAFolderTheUserCanWriteIsRefusedNamingThatFolder() throws Exception {
        // etc itself is read-only, but the folder above it is the test user's.
        Path file = targetFile("deep.conf", ORIGINAL);

        SudoEditService.UserWritableFolderException refused = org.testng.Assert.expectThrows(
            SudoEditService.UserWritableFolderException.class,
            () -> new SudoEditService(runner).refuseUserWritableFolder(file.toString()));
        assertThat(refused).hasMessageThat().contains(dir.toString());
    }

    @Test
    public void aFileWhoseFoldersOnlyRootCanWriteIsNotRefused() throws Exception {
        if ("root".equals(System.getProperty("user.name"))) {
            throw new SkipException("root can write every folder");
        }
        new SudoEditService(runner).refuseUserWritableFolder("/usr/bin/env");
    }

    @Test
    public void aFolderSwappedForALinkAfterOpeningStopsTheWriteBack() throws Exception {
        Path file = targetFile("swap.conf", ORIGINAL);
        Path elsewhere = Files.createDirectories(dir.resolve("elsewhere-" + System.nanoTime()));
        Path decoy = elsewhere.resolve("swap.conf");
        Files.writeString(decoy, "decoy\n");
        try (SudoEditSession edit = open(file, SECRET)) {
            Files.writeString(edit.localFile(), "changed\n");
            etc.toFile().setWritable(true, true);
            Path moved = dir.resolve(etc.getFileName() + "-moved");
            Files.move(etc, moved);
            Files.createSymbolicLink(etc, elsewhere);
            try {
                expectIo(edit::forceUpload);
                assertThat(Files.readString(decoy)).isEqualTo("decoy\n");
            } finally {
                Files.delete(etc);
                Files.move(moved, etc);
            }
        }
        assertThat(Files.readString(file)).isEqualTo(ORIGINAL);
    }

    @Test
    public void inspectTreatsAFileReachedThroughALinkedFolderLikeALink() throws Exception {
        Path file = targetFile("real.conf", ORIGINAL);
        Path linkedFolder = dir.resolve("linked-" + System.nanoTime());
        Files.createSymbolicLink(linkedFolder, etc);

        SudoEditService.Target target = new SudoEditService(runner)
            .inspect(linkedFolder.resolve("real.conf").toString(), Optional.of(SECRET.toCharArray()));
        assertThat(target.kind()).isEqualTo(SudoEditService.TargetKind.LINK);
        assertThat(target.resolvedPath()).isEqualTo(file.toString());
        assertThat(new SudoEditService(runner).inspect(file.toString(), Optional.of(SECRET.toCharArray())).kind())
            .isEqualTo(SudoEditService.TargetKind.FILE);
    }

    @Test
    public void hostileNamesAreReadAndWrittenLiterally() throws Exception {
        String name = "-it's $(touch pwned) `id` \"x\".conf";
        Path file = targetFile(name, ORIGINAL);

        try (SudoEditSession edit = open(file, SECRET)) {
            assertThat(Files.readString(edit.localFile())).isEqualTo(ORIGINAL);
            Files.writeString(edit.localFile(), "literal\n");
            assertThat(edit.upload()).isEqualTo(RemoteEditSession.UploadResult.UPLOADED);
        }
        assertThat(Files.readString(file)).isEqualTo("literal\n");
        assertThat(Files.exists(dir.resolve("pwned"))).isFalse();
        assertThat(Files.exists(etc.resolve("pwned"))).isFalse();
        assertThat(Files.exists(Path.of("pwned"))).isFalse();
    }

    private interface IoCall {
        void run() throws IOException;
    }

    private IOException expectIo(IoCall call) {
        try {
            call.run();
        } catch (IOException e) {
            errorTexts.add(String.valueOf(e.getMessage()));
            return e;
        }
        throw new AssertionError("expected an IOException");
    }
}
