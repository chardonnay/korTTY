package de.kortty.core.remote.search;

import de.kortty.core.remote.RemoteCommandCancellation;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.testng.SkipException;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

import static com.google.common.truth.Truth.assertThat;

/**
 * Walks a real tree over a loopback SFTP server: 3 000 files in nested folders plus a symlink loop
 * and a symlink to a folder outside the tree.
 */
public class SftpWalkStrategyIntegrationTest {

    private Path dir;
    private Path tree;
    private SshServer server;
    private SshClient client;
    private ClientSession session;
    private SftpClient sftp;
    private boolean symlinks;

    @BeforeClass
    public void setUp() throws IOException {
        dir = Files.createTempDirectory("kortty-walk-test");
        tree = Files.createDirectories(dir.resolve("root"));
        Path outside = Files.createDirectories(dir.resolve("outside"));
        Files.writeString(outside.resolve("secret.log"), "x");
        for (int folder = 0; folder < 30; folder++) {
            Path sub = Files.createDirectories(tree.resolve("d" + folder).resolve("inner"));
            for (int file = 0; file < 100; file++) {
                Path target = file % 2 == 0 ? sub : sub.getParent();
                Files.writeString(target.resolve("f" + folder + "-" + file + (file % 10 == 0 ? ".LOG" : ".txt")), "");
            }
        }
        Files.createDirectories(tree.resolve("a/b/c/d/e"));
        Files.writeString(tree.resolve("a/b/c/d/e/deep.log"), "");
        try {
            Files.createSymbolicLink(tree.resolve("loop"), Path.of("."));
            Files.createSymbolicLink(tree.resolve("d0/back"), Path.of(".."));
            Files.createSymbolicLink(tree.resolve("escape"), outside);
            symlinks = true;
        } catch (UnsupportedOperationException | IOException e) {
            symlinks = false;
        }

        server = SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1");
        server.setPort(0);
        server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(dir.resolve("hostkey.ser")));
        server.setPasswordAuthenticator((user, password, s) -> true);
        server.setFileSystemFactory(new VirtualFileSystemFactory(tree));
        server.setSubsystemFactories(List.of(new SftpSubsystemFactory()));
        server.start();
        client = SshClient.setUpDefaultClient();
        client.setServerKeyVerifier((s, address, key) -> true);
        client.start();
        session = client.connect("tester", "127.0.0.1", server.getPort()).verify(Duration.ofSeconds(10)).getSession();
        session.addPasswordIdentity("pw");
        session.auth().verify(Duration.ofSeconds(10));
        sftp = SftpClientFactory.instance().createSftpClient(session);
    }

    @AfterClass(alwaysRun = true)
    public void tearDown() throws IOException {
        if (sftp != null) {
            sftp.close();
        }
        if (client != null) {
            client.stop();
        }
        if (server != null) {
            server.stop(true);
        }
        if (dir != null) {
            try (var paths = Files.walk(dir)) {
                paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    private static Predicate<String> logs() {
        return name -> name.toLowerCase(java.util.Locale.ROOT).endsWith(".log");
    }

    private RemoteSearchRequest request(Predicate<String> matcher) {
        return RemoteSearchRequest.of("/", "*.log", true, matcher);
    }

    @Test(timeOut = 60_000)
    public void findsEveryMatchCaseInsensitivelyWithoutFollowingSymlinks() throws IOException {
        List<RemoteSearchHit> hits = new ArrayList<>();
        List<Integer> batchSizes = new ArrayList<>();
        RemoteSearchOutcome outcome = new SftpWalkStrategy(RemoteTreeReader.of(sftp)).search(request(logs()),
            batch -> {
                batchSizes.add(batch.size());
                hits.addAll(batch);
            }, new RemoteCommandCancellation());

        assertThat(outcome.strategy()).isEqualTo(RemoteSearchOutcome.Strategy.SFTP_WALK);
        assertThat(outcome.stop()).isEqualTo(RemoteSearchOutcome.StopReason.COMPLETED);
        // 30 folders x 10 .LOG files, plus the deep one.
        assertThat(hits).hasSize(301);
        assertThat(outcome.results()).isEqualTo(301);
        assertThat(batchSizes.stream().allMatch(size -> size <= 100)).isTrue();
        assertThat(hits.stream().noneMatch(hit -> hit.path().contains("/loop/") || hit.path().contains("/back/")
            || hit.path().contains("secret"))).isTrue();
        RemoteSearchHit deep = hits.stream().filter(hit -> hit.name().equals("deep.log")).findFirst().orElseThrow();
        assertThat(deep.relativePath()).isEqualTo("a/b/c/d/e/deep.log");
        assertThat(deep.kind()).isEqualTo(RemoteSearchHit.Kind.FILE);
        assertThat(deep.parentPath()).isEqualTo("/a/b/c/d/e");
    }

    @Test(timeOut = 60_000)
    public void reportsSymlinksAsLinks() throws IOException {
        if (!symlinks) {
            throw new SkipException("no symlinks on this file system");
        }
        List<RemoteSearchHit> hits = new ArrayList<>();
        new SftpWalkStrategy(RemoteTreeReader.of(sftp)).search(
            RemoteSearchRequest.of("/", "loop", false, name -> name.equals("loop") || name.equals("escape")),
            hits::addAll, new RemoteCommandCancellation());
        assertThat(hits.stream().map(RemoteSearchHit::kind).distinct().toList())
            .containsExactly(RemoteSearchHit.Kind.SYMLINK);
        assertThat(hits).hasSize(2);
    }

    @Test(timeOut = 60_000)
    public void honoursTheDepthResultAndEntryCaps() throws IOException {
        SftpWalkStrategy walk = new SftpWalkStrategy(RemoteTreeReader.of(sftp));
        List<RemoteSearchHit> shallow = new ArrayList<>();
        walk.search(request(logs()).withLimits(2, 5000, Duration.ofSeconds(60), 1_000_000),
            shallow::addAll, new RemoteCommandCancellation());
        // The .LOG files sit in d*/inner (level 3); level 2 holds only .txt files.
        assertThat(shallow).isEmpty();
        List<RemoteSearchHit> three = new ArrayList<>();
        walk.search(request(logs()).withLimits(3, 5000, Duration.ofSeconds(60), 1_000_000),
            three::addAll, new RemoteCommandCancellation());
        assertThat(three).hasSize(300);
        assertThat(three.stream().noneMatch(hit -> hit.name().equals("deep.log"))).isTrue();

        List<RemoteSearchHit> limited = new ArrayList<>();
        RemoteSearchOutcome atLimit = walk.search(request(logs()).withLimits(10, 25, Duration.ofSeconds(60), 1_000_000),
            limited::addAll, new RemoteCommandCancellation());
        assertThat(atLimit.stop()).isEqualTo(RemoteSearchOutcome.StopReason.RESULT_LIMIT);
        assertThat(limited).hasSize(25);

        RemoteSearchOutcome entries = walk.search(request(logs()).withLimits(10, 5000, Duration.ofSeconds(60), 500),
            batch -> { }, new RemoteCommandCancellation());
        assertThat(entries.stop()).isEqualTo(RemoteSearchOutcome.StopReason.ENTRY_LIMIT);
    }

    @Test(timeOut = 60_000)
    public void stopsAtTheTimeLimit() throws IOException {
        AtomicLong now = new AtomicLong();
        RemoteTreeReader slow = new RemoteTreeReader() {
            private final RemoteTreeReader real = RemoteTreeReader.of(sftp);

            @Override
            public List<SftpClient.DirEntry> list(String folder) throws IOException {
                now.addAndGet(Duration.ofSeconds(1).toNanos());
                return real.list(folder);
            }

            @Override
            public SftpClient.Attributes lstat(String path) throws IOException {
                return real.lstat(path);
            }
        };
        RemoteSearchOutcome outcome = new SftpWalkStrategy(slow, now::get).search(
            request(logs()).withLimits(10, 5000, Duration.ofSeconds(5), 1_000_000), batch -> { },
            new RemoteCommandCancellation());
        assertThat(outcome.stop()).isEqualTo(RemoteSearchOutcome.StopReason.TIME_LIMIT);
    }

    @Test(timeOut = 60_000)
    public void cancelStopsWithinOneBatch() throws IOException {
        RemoteCommandCancellation cancellation = new RemoteCommandCancellation();
        List<RemoteSearchHit> hits = new ArrayList<>();
        int[] batches = {0};
        RemoteSearchOutcome outcome = new SftpWalkStrategy(RemoteTreeReader.of(sftp)).search(
            RemoteSearchRequest.of("/", "f", false, name -> true), batch -> {
                hits.addAll(batch);
                batches[0]++;
                cancellation.cancel();
            }, cancellation);
        assertThat(outcome.stop()).isEqualTo(RemoteSearchOutcome.StopReason.CANCELLED);
        assertThat(batches[0]).isEqualTo(1);
        assertThat(hits).hasSize(100);
    }
}
