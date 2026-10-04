package de.kortty.core;

import org.apache.sshd.client.ClientFactoryManager;
import org.apache.sshd.client.session.ClientSessionImpl;
import org.apache.sshd.client.session.SessionFactory;
import org.apache.sshd.common.io.IoSession;
import org.apache.sshd.common.util.Readable;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * The decision loop of {@link SshTtyConnector}'s liveness probe, kept free of threads and sockets
 * so it can be driven by a fake clock.
 *
 * <p>A probe is one SSH global request with a reply timeout. The loop sleeps one interval, sends a
 * probe and, after a reply, sleeps again. After a missing reply it probes again right away; two
 * consecutive <em>counted</em> misses declare the transport dead.</p>
 *
 * <p><strong>Shared sessions.</strong> The terminal's SSH session may also carry bulk data (an SFTP
 * transfer on a borrowed session, a large paste, a busy shell). On a slow link that data sits in
 * front of the probe in the same TCP stream and can delay the reply past the timeout although the
 * peer is perfectly alive. A missed reply therefore only counts when <em>no inbound byte</em>
 * arrived while that probe was waiting: any inbound traffic proves the peer and the network are
 * alive, so the miss is excused and the count starts over. Outbound traffic is deliberately not an
 * excuse: a dead link still accepts writes into the local socket buffer, so it proves nothing.</p>
 *
 * <p><strong>Detection time.</strong> A dead link delivers no inbound bytes, so a probe sent after
 * the death always counts. The window of a probe is only its own wait (not the sleep before it),
 * and after any miss the next probe follows without a sleep. A death during a sleep is therefore
 * confirmed after at most {@code interval + 2 * timeout}; a death while a probe is waiting (traffic
 * until the death excuses that probe) after at most {@code 3 * timeout}. With the connector's
 * 3 s / 3 s values both stay within 10 seconds.</p>
 *
 * <p>The kill-switch only arms after the server answered one probe: a server that never replies to
 * global requests (violating RFC 4254) must not have healthy sessions killed.</p>
 */
final class SshLivenessProbe {

    /** Consecutive counted misses that declare the transport dead. */
    static final int MISSES_TO_DECLARE_DEAD = 2;

    /** What the loop probes: one SSH session (or a fake in tests). */
    interface Transport {
        /** False once the session is closed or closing; the connection monitor reports that. */
        boolean isOpen();

        /** Sends one probe and blocks until its reply or its timeout; true when the peer replied. */
        boolean probe();

        /** A monotonically growing count of inbound bytes; only differences are compared. */
        long inboundBytes();
    }

    /** Waits one interval; {@link Thread#sleep(long)} in production, a clock advance in tests. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    enum Outcome {
        /** Two consecutive probes got no reply while the transport was silent. */
        DEAD,
        /** The connector stopped or the session closed by itself. */
        STOPPED
    }

    private final long intervalMillis;
    private final Sleeper sleeper;

    SshLivenessProbe(long intervalMillis, Sleeper sleeper) {
        if (intervalMillis <= 0) {
            throw new IllegalArgumentException("intervalMillis must be positive");
        }
        this.intervalMillis = intervalMillis;
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
    }

    /**
     * Probes until {@code running} turns false, the transport closes, or the transport is declared
     * dead.
     */
    Outcome run(Transport transport, BooleanSupplier running) throws InterruptedException {
        boolean armed = false;
        boolean sleepFirst = true;
        int countedMisses = 0;
        while (running.getAsBoolean()) {
            if (sleepFirst) {
                sleeper.sleep(intervalMillis);
            }
            if (!running.getAsBoolean() || !transport.isOpen()) {
                return Outcome.STOPPED;
            }
            long inboundBefore = transport.inboundBytes();
            if (transport.probe()) {
                armed = true;
                countedMisses = 0;
                sleepFirst = true;
                continue;
            }
            if (!armed) {
                sleepFirst = true;
                continue;
            }
            sleepFirst = false; // confirm a miss right away
            if (transport.inboundBytes() != inboundBefore) {
                countedMisses = 0; // the peer is talking: the reply is just queued behind data
                continue;
            }
            countedMisses++;
            if (countedMisses >= MISSES_TO_DECLARE_DEAD) {
                return running.getAsBoolean() ? Outcome.DEAD : Outcome.STOPPED;
            }
        }
        return Outcome.STOPPED;
    }

    /**
     * A client session factory whose sessions add the size of every raw inbound read to
     * {@code inboundBytes} before SSHD decodes it. Install it with
     * {@code client.setSessionFactory(...)} before {@code client.start()}.
     */
    static SessionFactory inboundCountingSessionFactory(ClientFactoryManager client, AtomicLong inboundBytes) {
        Objects.requireNonNull(inboundBytes, "inboundBytes");
        return new SessionFactory(client) {
            @Override
            protected ClientSessionImpl doCreateSession(IoSession ioSession) throws Exception {
                return new ClientSessionImpl(getClient(), ioSession) {
                    @Override
                    public void messageReceived(Readable buffer) throws Exception {
                        inboundBytes.addAndGet(Math.max(1, buffer.available()));
                        super.messageReceived(buffer);
                    }
                };
            }
        };
    }
}
