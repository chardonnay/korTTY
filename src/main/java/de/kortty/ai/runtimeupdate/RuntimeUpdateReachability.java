package de.kortty.ai.runtimeupdate;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.PortUnreachableException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;

/**
 * Tells apart "korTTY could not reach the release server" from "the release server answered
 * something wrong".
 *
 * <p>A runtime update check is a background network call made at application start, so it runs
 * whenever the machine happens to be offline, behind a captive portal, or on a link that is not up
 * yet. That is the normal state of a laptop, not a defect in korTTY: reporting it as a failed
 * update — an {@code ERROR} with a stack trace in the log, a failed state in the UI — trains the
 * user to ignore the one report that does mean something.
 *
 * <p>Only failures of the transport are treated as unreachable. A TLS failure is deliberately
 * <em>not</em> in that set: a handshake that cannot be completed may be an interception attempt,
 * and a signed-index client must keep reporting that at full volume. The same holds for an HTTP
 * error status or an index that fails signature verification — the server was reached, and what it
 * said was wrong.
 */
public final class RuntimeUpdateReachability {

    private RuntimeUpdateReachability() {
    }

    /**
     * Returns {@code true} when {@code failure}'s cause chain shows the release server was never
     * reached: a connect or read timeout, a refused connection, or an unresolvable/unroutable host.
     */
    public static boolean isUnreachable(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof HttpTimeoutException          // includes HttpConnectTimeoutException
                || current instanceof SocketTimeoutException
                || current instanceof ConnectException           // includes the JDK's connect-timeout wrapper
                || current instanceof UnknownHostException
                || current instanceof NoRouteToHostException
                || current instanceof PortUnreachableException) {
                return true;
            }
            if (current.getCause() == current) {
                break; // self-referential chain; stop rather than spin
            }
        }
        return false;
    }
}
