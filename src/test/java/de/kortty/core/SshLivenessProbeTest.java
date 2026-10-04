package de.kortty.core;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.server.SshServer;
import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static com.google.common.truth.Truth.assertThat;

/**
 * The liveness probe's decision rules, driven by a fake clock: a link that is slow but still
 * delivering data never counts as dead, a silent one does, and detection stays within 10 seconds.
 */
public class SshLivenessProbeTest {

    private static final long INTERVAL = SshTtyConnector.LIVENESS_PROBE_INTERVAL_MS;
    private static final long TIMEOUT = SshTtyConnector.LIVENESS_PROBE_TIMEOUT_MS;
    private static final long MAX_DETECTION_MS = 10_000;

    /**
     * A simulated link on a fake clock. Replies take {@code replyLatency}; when that exceeds the
     * timeout the probe misses. Inbound data arrives every {@code trafficEveryMs} from
     * {@code trafficFrom} until the link dies at {@code deathAt}; afterwards nothing arrives.
     */
    private static final class FakeLink implements SshLivenessProbe.Transport, SshLivenessProbe.Sleeper {
        long now;
        long deathAt = Long.MAX_VALUE;
        long replyLatency = 50;
        long trafficFrom = Long.MAX_VALUE;
        long trafficUntil = Long.MAX_VALUE;
        long trafficEveryMs = 100;
        long stopAt = Long.MAX_VALUE;
        int probes;

        @Override
        public void sleep(long millis) {
            now += millis;
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public boolean probe() {
            probes++;
            boolean alive = now < deathAt;
            if (alive && replyLatency <= TIMEOUT) {
                now += replyLatency;
                return true;
            }
            now += TIMEOUT;
            return false;
        }

        @Override
        public long inboundBytes() {
            long end = Math.min(now, Math.min(deathAt, trafficUntil));
            if (end <= trafficFrom) {
                return 0;
            }
            return (end - trafficFrom) / trafficEveryMs + 1;
        }

        boolean running() {
            return now < stopAt;
        }
    }

    private static SshLivenessProbe.Outcome run(FakeLink link) throws InterruptedException {
        return new SshLivenessProbe(INTERVAL, link).run(link, link::running);
    }

    @Test
    public void missedRepliesWithInboundTrafficInBetweenDoNotClose() throws InterruptedException {
        FakeLink link = new FakeLink();
        link.stopAt = 120_000;
        // Healthy at first so the kill-switch arms, then an SFTP download on a slow link delays
        // every reply past the timeout while data keeps arriving.
        link.trafficFrom = 5_000;
        SshLivenessProbe.Outcome outcome = new SshLivenessProbe(INTERVAL, millis -> {
            link.sleep(millis);
            if (link.now >= 5_000) {
                link.replyLatency = 10_000;
            }
        }).run(link, link::running);

        assertThat(outcome).isEqualTo(SshLivenessProbe.Outcome.STOPPED);
        assertThat(link.probes).isGreaterThan(20);
    }

    @Test
    public void twoMissedRepliesOnASilentLinkCloseAsBefore() throws InterruptedException {
        FakeLink link = new FakeLink();
        link.deathAt = 7_000;
        link.stopAt = 120_000;

        assertThat(run(link)).isEqualTo(SshLivenessProbe.Outcome.DEAD);
        assertThat(link.now - link.deathAt).isAtMost(MAX_DETECTION_MS);
    }

    @Test
    public void slowRepliesStopCountingOnceTheLinkFallsSilent() throws InterruptedException {
        FakeLink link = new FakeLink();
        link.trafficFrom = 0;
        link.trafficUntil = 20_000; // the transfer ends; replies are still too slow
        link.stopAt = 120_000;
        SshLivenessProbe.Outcome outcome = new SshLivenessProbe(INTERVAL, millis -> {
            link.sleep(millis);
            if (link.now >= 5_000) {
                link.replyLatency = 10_000;
            }
        }).run(link, link::running);

        assertThat(outcome).isEqualTo(SshLivenessProbe.Outcome.DEAD);
        assertThat(link.now).isAtMost(link.trafficUntil + MAX_DETECTION_MS);
    }

    @Test
    public void detectionStaysWithinTenSecondsWhereverTheLinkDies() throws InterruptedException {
        for (boolean bulkTraffic : new boolean[] {false, true}) {
            for (long death = 3_100; death <= 40_000; death += 50) {
                FakeLink link = new FakeLink();
                link.deathAt = death;
                link.stopAt = death + 60_000;
                if (bulkTraffic) {
                    link.trafficFrom = 0; // data flows right up to the moment the link dies
                    link.trafficEveryMs = 10;
                }

                SshLivenessProbe.Outcome outcome = run(link);

                assertThat(outcome).isEqualTo(SshLivenessProbe.Outcome.DEAD);
                assertThat(link.now - death).isAtMost(MAX_DETECTION_MS);
            }
        }
    }

    @Test
    public void neverArmsWhenTheServerNeverAnswers() throws InterruptedException {
        FakeLink link = new FakeLink();
        link.deathAt = 0; // RFC-violating server: no reply to global requests at all
        link.stopAt = 60_000;

        assertThat(run(link)).isEqualTo(SshLivenessProbe.Outcome.STOPPED);
    }

    @Test
    public void stopsWhenTheSessionClosesByItself() throws InterruptedException {
        AtomicInteger checks = new AtomicInteger();
        SshLivenessProbe.Transport closed = new SshLivenessProbe.Transport() {
            @Override
            public boolean isOpen() {
                checks.incrementAndGet();
                return false;
            }

            @Override
            public boolean probe() {
                throw new AssertionError("a closed session is never probed");
            }

            @Override
            public long inboundBytes() {
                return 0;
            }
        };

        assertThat(new SshLivenessProbe(INTERVAL, millis -> { }).run(closed, () -> true))
            .isEqualTo(SshLivenessProbe.Outcome.STOPPED);
        assertThat(checks.get()).isEqualTo(1);
    }

    @Test
    public void countingSessionFactoryCountsInboundBytesOfARealSession() throws Exception {
        Path tmp = Files.createTempDirectory("kortty-liveness");
        SshServer server = LoopbackSshServers.start(tmp.resolve("host.ser"), ConcurrentHashMap.newKeySet(),
            new AtomicInteger(), false);
        SshClient client = SshClient.setUpDefaultClient();
        AtomicLong inbound = new AtomicLong();
        try {
            client.setServerKeyVerifier((session, address, key) -> true);
            client.setSessionFactory(SshLivenessProbe.inboundCountingSessionFactory(client, inbound));
            client.start();
            try (ClientSession session = client.connect("tester", "127.0.0.1", server.getPort())
                    .verify(Duration.ofSeconds(10)).getSession()) {
                session.addPasswordIdentity("secret");
                session.auth().verify(Duration.ofSeconds(10));
                long afterAuth = inbound.get();
                assertThat(afterAuth).isGreaterThan(0L);

                // The server answers the unknown global request with a failure; that reply counts.
                org.apache.sshd.common.util.buffer.Buffer request = session.createBuffer(
                    org.apache.sshd.common.SshConstants.SSH_MSG_GLOBAL_REQUEST, 64);
                request.putString("keepalive@kortty.de");
                request.putBoolean(true);
                session.request("keepalive@kortty.de", request, 10_000);
                assertThat(inbound.get()).isGreaterThan(afterAuth);
            }
        } finally {
            client.stop();
            server.stop(true);
            try (var files = Files.walk(tmp)) {
                files.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }
}
