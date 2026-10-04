package de.kortty.core.remote;

import de.kortty.core.remote.search.RemoteSearchHit;
import de.kortty.core.remote.search.RemoteSearchOutcome;
import de.kortty.core.remote.search.RemoteSearchRequest;
import de.kortty.core.remote.search.RemoteSearchService;
import de.kortty.core.remote.search.RemoteTreeReader;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.testng.SkipException;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import static com.google.common.truth.Truth.assertThat;

/**
 * {@link RemoteSearchService} against loopback servers whose exec channels run real local shells
 * (and the real {@code find}) and whose SFTP subsystem serves the local file system. Unix only.
 */
public class RemoteSearchServiceTest {

    private Path dir;
    private Path bin;
    private Path tree;
    private ExecLoopbackServer server;
    private ClientSession session;
    private SftpClient sftp;

    @BeforeMethod
    public void setUp() throws IOException {
        if (!Files.isExecutable(Path.of("/bin/sh")) || System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            throw new SkipException("needs a POSIX shell");
        }
        dir = Files.createTempDirectory("kortty-search-test").toRealPath();
        bin = Files.createDirectories(dir.resolve("bin"));
        tree = Files.createDirectories(dir.resolve("tree"));
        for (int i = 0; i < 40; i++) {
            Path sub = Files.createDirectories(tree.resolve("sub" + i));
            Files.writeString(sub.resolve("Report-" + i + ".LOG"), "");
            Files.writeString(sub.resolve("notes-" + i + ".txt"), "");
        }
        Files.createSymbolicLink(tree.resolve("loop"), Path.of("."));
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown() throws IOException {
        if (sftp != null) {
            sftp.close();
            sftp = null;
        }
        if (server != null) {
            server.close();
            server = null;
        }
        if (dir != null) {
            try (var paths = Files.walk(dir)) {
                paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    private RemoteSearchService connect(boolean exec) throws IOException {
        server = new ExecLoopbackServer(dir.resolve("hostkey.ser"), bin, exec, true);
        session = server.connect();
        sftp = SftpClientFactory.instance().createSftpClient(session);
        return new RemoteSearchService(new RemoteCommandRunner(() -> session), RemoteTreeReader.of(sftp));
    }

    private RemoteSearchRequest logs(String root) {
        return RemoteSearchRequest.of(root, "*.log", true,
            name -> name.toLowerCase(Locale.ROOT).endsWith(".log"));
    }

    private void fakeFind(String body) throws IOException {
        Path find = bin.resolve("find");
        Files.writeString(find, "#!/bin/sh\n" + body + "\n");
        Files.setPosixFilePermissions(find, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    @Test(timeOut = 60_000)
    public void findSearchesTheTreeCaseInsensitivelyWithoutFollowingSymlinks() throws IOException {
        RemoteSearchService service = connect(true);
        List<RemoteSearchHit> hits = new ArrayList<>();
        RemoteSearchOutcome outcome = service.search(logs(tree.toString()), hits::addAll, new RemoteCommandCancellation());
        assertThat(outcome.strategy()).isEqualTo(RemoteSearchOutcome.Strategy.FIND);
        assertThat(outcome.stop()).isEqualTo(RemoteSearchOutcome.StopReason.COMPLETED);
        assertThat(hits).hasSize(40);
        assertThat(hits.stream().noneMatch(hit -> hit.path().contains("/loop/"))).isTrue();
        assertThat(hits.stream().map(RemoteSearchHit::relativePath).toList()).contains("sub7/Report-7.LOG");
    }

    @Test(timeOut = 60_000)
    public void anExecFailureFallsBackToTheWalk() throws IOException {
        RemoteSearchService service = connect(false);
        List<RemoteSearchHit> hits = new ArrayList<>();
        RemoteSearchOutcome outcome = service.search(logs(tree.toString()), hits::addAll, new RemoteCommandCancellation());
        assertThat(outcome.strategy()).isEqualTo(RemoteSearchOutcome.Strategy.SFTP_WALK);
        assertThat(outcome.stop()).isEqualTo(RemoteSearchOutcome.StopReason.COMPLETED);
        assertThat(hits).hasSize(40);
        assertThat(hits.stream().allMatch(hit -> hit.kind() == RemoteSearchHit.Kind.FILE)).isTrue();
    }

    @Test(timeOut = 60_000)
    public void aFindThatFailsWithoutResultsFallsBackToTheWalk() throws IOException {
        fakeFind("echo 'find: unrecognized: -P' >&2; exit 1");
        RemoteSearchService service = connect(true);
        List<RemoteSearchHit> hits = new ArrayList<>();
        RemoteSearchOutcome outcome = service.search(logs(tree.toString()), hits::addAll, new RemoteCommandCancellation());
        assertThat(outcome.strategy()).isEqualTo(RemoteSearchOutcome.Strategy.SFTP_WALK);
        assertThat(hits).hasSize(40);
    }

    @Test(timeOut = 60_000)
    public void theLimitClosesTheChannel() throws IOException {
        // Prints 50 matches, then would hang for a long time: only a closed channel ends it early.
        fakeFind("i=0; while [ $i -lt 50 ]; do printf '/r/f%s.log\\000' $i; i=$((i+1)); done; sleep 40");
        RemoteSearchService service = connect(true);
        RemoteSearchRequest request = logs("/r").withLimits(10, 10, Duration.ofSeconds(60), 1000);
        List<RemoteSearchHit> hits = new ArrayList<>();
        int closedBefore = server.closedChannels();
        long start = System.nanoTime();
        RemoteSearchOutcome outcome = service.search(request, hits::addAll, new RemoteCommandCancellation());
        long elapsed = Duration.ofNanos(System.nanoTime() - start).toSeconds();
        assertThat(outcome.strategy()).isEqualTo(RemoteSearchOutcome.Strategy.FIND);
        assertThat(outcome.stop()).isEqualTo(RemoteSearchOutcome.StopReason.RESULT_LIMIT);
        assertThat(hits).hasSize(10);
        assertThat(hits.get(9).path()).isEqualTo("/r/f9.log");
        assertThat(elapsed).isLessThan(20L);
        // The probe and the find channel are both closed on the server.
        long deadline = System.currentTimeMillis() + 5000;
        while (server.closedChannels() - closedBefore < 2 && System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(server.closedChannels() - closedBefore).isAtLeast(2);
    }

    @Test(timeOut = 60_000)
    public void cancelEndsAFindSearch() throws IOException {
        fakeFind("printf '/r/a.log\\000'; sleep 40");
        RemoteSearchService service = connect(true);
        RemoteCommandCancellation cancellation = new RemoteCommandCancellation();
        List<RemoteSearchHit> hits = new ArrayList<>();
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            cancellation.cancel();
        });
        RemoteSearchOutcome outcome = service.search(logs("/r"), hits::addAll, cancellation);
        assertThat(outcome.stop()).isEqualTo(RemoteSearchOutcome.StopReason.CANCELLED);
        assertThat(hits).hasSize(1);
    }
}
