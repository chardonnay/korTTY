package de.kortty.core.sftp;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.common.util.buffer.Buffer;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.server.session.ServerSession;
import org.apache.sshd.sftp.SftpModuleProperties;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.apache.sshd.sftp.client.SftpVersionSelector;
import org.apache.sshd.sftp.server.SftpEventListener;
import org.apache.sshd.sftp.server.SftpFileSystemAccessor;
import org.apache.sshd.sftp.server.SftpSubsystem;
import org.apache.sshd.sftp.server.SftpSubsystemConfigurator;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A loopback-only Apache SSHD server with the SFTP subsystem rooted in a directory, for SFTP
 * integration tests of any package.
 *
 * <p>Options: hide every SFTP extension from the server's VERSION reply (a bare server without
 * {@code limits@openssh.com}, {@code posix-rename@openssh.com} and friends), pin the server's SFTP
 * version, slow each READ/WRITE down so pipelined requests visibly queue up, and replace the file
 * system accessor. Clients can force the negotiated version with
 * {@link SftpVersionSelector#fixedVersionSelector(int)} (see {@link #openSftp(ClientSession, int)}),
 * because MINA against MINA otherwise negotiates version 6 and never exercises the version 3 paths
 * OpenSSH servers use.
 *
 * <p>{@link #stats()} counts what the server sees: live SFTP channels, open handles and the most
 * requests that were waiting while a READ or WRITE ran (more than one proves pipelining).
 *
 * <p>Any password authenticates as long as it equals {@link #PASSWORD}; host keys are accepted
 * without a prompt. Test use only.
 */
public final class SftpLoopbackFixture implements AutoCloseable {

    public static final String USER = "tester";
    public static final String PASSWORD = "secret";
    /** For {@link #openSftp(ClientSession, int)}: let MINA negotiate as it likes. */
    public static final int DEFAULT_VERSION = 0;

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final Path root;
    private final SshServer server;
    private final Stats stats;
    private final SshClient client;
    private final List<ClientSession> sessions = new ArrayList<>();

    private SftpLoopbackFixture(Path root, SshServer server, Stats stats) {
        this.root = root;
        this.server = server;
        this.stats = stats;
        this.client = SshClient.setUpDefaultClient();
        this.client.setServerKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE);
        this.client.start();
    }

    /** Starts building a fixture whose remote root and host key live below {@code workDir}. */
    public static Builder builder(Path workDir) {
        return new Builder(workDir);
    }

    /** The directory the server shows as "/". */
    public Path root() {
        return root;
    }

    public int port() {
        return server.getPort();
    }

    public SshServer server() {
        return server;
    }

    public Stats stats() {
        return stats;
    }

    /** A new authenticated client session; closed with the fixture. */
    public ClientSession connect() throws IOException {
        ClientSession session = client.connect(USER, "127.0.0.1", port()).verify(TIMEOUT).getSession();
        synchronized (sessions) {
            sessions.add(session);
        }
        session.addPasswordIdentity(PASSWORD);
        session.auth().verify(TIMEOUT);
        return session;
    }

    /** An SFTP channel with MINA's default version negotiation on a new session. */
    public SftpClient openSftp() throws IOException {
        return openSftp(connect(), DEFAULT_VERSION);
    }

    /**
     * An SFTP channel on {@code session}; {@code version} &gt; 0 forces that version with
     * {@link SftpVersionSelector#fixedVersionSelector(int)}, {@link #DEFAULT_VERSION} negotiates.
     */
    public SftpClient openSftp(ClientSession session, int version) throws IOException {
        SftpVersionSelector selector = version > 0
            ? SftpVersionSelector.fixedVersionSelector(version)
            : SftpVersionSelector.CURRENT;
        return SftpClientFactory.instance().createSftpClient(session, selector);
    }

    @Override
    public void close() throws IOException {
        synchronized (sessions) {
            for (ClientSession session : sessions) {
                session.close(true);
            }
            sessions.clear();
        }
        client.stop();
        server.stop(true);
    }

    /** Fixture options. */
    public static final class Builder {
        private final Path workDir;
        private boolean advertiseExtensions = true;
        private Integer serverVersion;
        private long requestDelayMillis;
        private SftpFileSystemAccessor accessor;

        private Builder(Path workDir) {
            this.workDir = Objects.requireNonNull(workDir, "workDir");
        }

        /** {@code false}: the VERSION reply carries no extensions at all. */
        public Builder advertiseExtensions(boolean advertise) {
            this.advertiseExtensions = advertise;
            return this;
        }

        /** Pins the highest SFTP version the server offers (MINA's {@code SFTP_VERSION}). */
        public Builder serverVersion(int version) {
            this.serverVersion = version;
            return this;
        }

        /** Sleeps this long in every READ and WRITE, so requests sent ahead queue up visibly. */
        public Builder requestDelayMillis(long millis) {
            this.requestDelayMillis = millis;
            return this;
        }

        /** Replaces the SFTP file system accessor (for sinks, failure injection and the like). */
        public Builder fileSystemAccessor(SftpFileSystemAccessor accessor) {
            this.accessor = accessor;
            return this;
        }

        public SftpLoopbackFixture start() throws IOException {
            Path root = Files.createDirectories(workDir.resolve("remote"));
            Stats stats = new Stats();
            SshServer server = SshServer.setUpDefaultServer();
            server.setHost("127.0.0.1");
            server.setPort(0);
            server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(workDir.resolve("fixture-host.ser")));
            server.setPasswordAuthenticator((username, password, session) -> PASSWORD.equals(password));
            server.setFileSystemFactory(new VirtualFileSystemFactory(root));
            if (serverVersion != null) {
                SftpModuleProperties.SFTP_VERSION.set(server, serverVersion);
            }
            FixtureSubsystemFactory factory = new FixtureSubsystemFactory(stats, advertiseExtensions, requestDelayMillis);
            if (accessor != null) {
                factory.setFileSystemAccessor(accessor);
            }
            factory.addSftpEventListener(stats);
            server.setSubsystemFactories(List.of(factory));
            server.start();
            return new SftpLoopbackFixture(root, server, stats);
        }
    }

    /** What the server side observed. */
    public static final class Stats implements SftpEventListener {
        private final Set<FixtureSubsystem> live = ConcurrentHashMap.newKeySet();
        private final AtomicInteger channelsOpened = new AtomicInteger();
        private final AtomicInteger maxInFlightReads = new AtomicInteger();
        private final AtomicInteger maxInFlightWrites = new AtomicInteger();
        private final AtomicInteger reads = new AtomicInteger();
        private final AtomicInteger writes = new AtomicInteger();
        private final AtomicReference<Integer> negotiatedVersion = new AtomicReference<>();

        /** SFTP channels currently open on the server. */
        public int openChannels() {
            return live.size();
        }

        /** SFTP channels the server ever initialized. */
        public int channelsOpened() {
            return channelsOpened.get();
        }

        /** File and directory handles currently open, over all live channels. */
        public int openHandles() {
            int count = 0;
            for (FixtureSubsystem subsystem : live) {
                count += subsystem.openHandleCount();
            }
            return count;
        }

        /** Most requests that were outstanding (the running one included) while a READ ran. */
        public int maxInFlightReads() {
            return maxInFlightReads.get();
        }

        /** Most requests that were outstanding (the running one included) while a WRITE ran. */
        public int maxInFlightWrites() {
            return maxInFlightWrites.get();
        }

        public int reads() {
            return reads.get();
        }

        public int writes() {
            return writes.get();
        }

        /** The version the last channel settled on, or {@code null} before any. */
        public Integer negotiatedVersion() {
            return negotiatedVersion.get();
        }

        @Override
        public void initialized(ServerSession session, int version) {
            channelsOpened.incrementAndGet();
            negotiatedVersion.set(version);
        }

        void attach(FixtureSubsystem subsystem) {
            live.add(subsystem);
        }

        void detach(FixtureSubsystem subsystem) {
            live.remove(subsystem);
        }

        void recordRead(int inFlight) {
            reads.incrementAndGet();
            maxInFlightReads.accumulateAndGet(inFlight, Math::max);
        }

        void recordWrite(int inFlight) {
            writes.incrementAndGet();
            maxInFlightWrites.accumulateAndGet(inFlight, Math::max);
        }
    }

    private static final class FixtureSubsystemFactory extends SftpSubsystemFactory {
        private final Stats stats;
        private final boolean advertiseExtensions;
        private final long requestDelayMillis;

        FixtureSubsystemFactory(Stats stats, boolean advertiseExtensions, long requestDelayMillis) {
            this.stats = stats;
            this.advertiseExtensions = advertiseExtensions;
            this.requestDelayMillis = requestDelayMillis;
        }

        @Override
        public Command createSubsystem(ChannelSession channel) throws IOException {
            FixtureSubsystem subsystem = new FixtureSubsystem(channel, this, stats, advertiseExtensions,
                requestDelayMillis);
            for (SftpEventListener listener : getRegisteredListeners()) {
                subsystem.addSftpEventListener(listener);
            }
            return subsystem;
        }
    }

    private static final class FixtureSubsystem extends SftpSubsystem {
        private final Stats stats;
        private final boolean advertiseExtensions;
        private final long requestDelayMillis;

        FixtureSubsystem(ChannelSession channel, SftpSubsystemConfigurator configurator, Stats stats,
                boolean advertiseExtensions, long requestDelayMillis) {
            super(channel, configurator);
            this.stats = stats;
            this.advertiseExtensions = advertiseExtensions;
            this.requestDelayMillis = requestDelayMillis;
            stats.attach(this);
        }

        int openHandleCount() {
            return handles.size();
        }

        @Override
        protected void appendExtensions(Buffer buffer, String supportedVersions) {
            if (advertiseExtensions) {
                super.appendExtensions(buffer, supportedVersions);
            }
        }

        @Override
        protected int doRead(int id, String handle, long offset, int length, byte[] data, int doff,
                AtomicReference<Boolean> eof) throws IOException {
            stats.recordRead(1 + requests.size());
            pause();
            return super.doRead(id, handle, offset, length, data, doff, eof);
        }

        @Override
        protected void doWrite(int id, String handle, long offset, int length, byte[] data, int doff, int remaining)
                throws IOException {
            stats.recordWrite(1 + requests.size());
            pause();
            super.doWrite(id, handle, offset, length, data, doff, remaining);
        }

        @Override
        public void destroy(ChannelSession channel) {
            try {
                super.destroy(channel);
            } finally {
                stats.detach(this);
            }
        }

        private void pause() throws IOException {
            if (requestDelayMillis <= 0) {
                return;
            }
            try {
                Thread.sleep(requestDelayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            }
        }
    }
}
