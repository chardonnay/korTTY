package de.kortty.core;

import org.testng.annotations.Test;

import java.io.IOException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

class LocalNetworkDiagnosticsTest {

    /** The exact shape of the failure: MINA wraps the socket error several layers deep. */
    private static Throwable wrappedNoRoute() {
        return new IOException("Failed to execute",
            new IllegalStateException("connect failed", new NoRouteToHostException("No route to host")));
    }

    @Test
    void findsNoRouteToHostThroughTheCauseChain() {
        assertThat(LocalNetworkDiagnostics.hasNoRouteToHost(wrappedNoRoute())).isTrue();
        assertThat(LocalNetworkDiagnostics.hasNoRouteToHost(new NoRouteToHostException("direct"))).isTrue();
    }

    @Test
    void ignoresUnrelatedFailures() {
        assertThat(LocalNetworkDiagnostics.hasNoRouteToHost(new SocketTimeoutException("timed out"))).isFalse();
        assertThat(LocalNetworkDiagnostics.hasNoRouteToHost(new IOException("connection refused"))).isFalse();
        assertThat(LocalNetworkDiagnostics.hasNoRouteToHost(null)).isFalse();
    }

    /** A cause chain that points at itself must not spin forever. */
    @Test
    void survivesSelfReferentialCauseChain() {
        class Looping extends RuntimeException {
            @Override public synchronized Throwable getCause() { return this; }
        }
        assertThat(LocalNetworkDiagnostics.hasNoRouteToHost(new Looping())).isFalse();
    }

    @Test
    void recognisesLocalNetworkAddresses() {
        // 10.211.55.5 is the Parallels default subnet — the address that surfaced this bug.
        assertThat(LocalNetworkDiagnostics.isLocalNetworkTarget("10.211.55.5")).isTrue();
        assertThat(LocalNetworkDiagnostics.isLocalNetworkTarget("192.168.1.20")).isTrue();
        assertThat(LocalNetworkDiagnostics.isLocalNetworkTarget("172.16.0.9")).isTrue();
        assertThat(LocalNetworkDiagnostics.isLocalNetworkTarget("169.254.3.4")).isTrue();
        assertThat(LocalNetworkDiagnostics.isLocalNetworkTarget("nas.local")).isTrue();
        assertThat(LocalNetworkDiagnostics.isLocalNetworkTarget("[fe80::1]")).isTrue();
        assertThat(LocalNetworkDiagnostics.isLocalNetworkTarget("fe80::1%en0")).isTrue();
        assertThat(LocalNetworkDiagnostics.isLocalNetworkTarget("fd00::5")).isTrue();
    }

    @Test
    void excludesPublicAndLoopbackTargets() {
        assertThat(LocalNetworkDiagnostics.isLocalNetworkTarget("93.184.216.34")).isFalse();
        assertThat(LocalNetworkDiagnostics.isLocalNetworkTarget("172.32.0.1")).isFalse();  // just outside 172.16/12
        assertWithMessage("loopback is exempt from the Local Network permission")
            .that(LocalNetworkDiagnostics.isLocalNetworkTarget("127.0.0.1")).isFalse();
        assertThat(LocalNetworkDiagnostics.isLocalNetworkTarget("::1")).isFalse();
        assertThat(LocalNetworkDiagnostics.isLocalNetworkTarget("")).isFalse();
        assertThat(LocalNetworkDiagnostics.isLocalNetworkTarget(null)).isFalse();
    }

    /**
     * A hostname must never be resolved here: error handling would block on DNS, and a name that
     * stops resolving because the network is down would be misjudged as non-local.
     */
    @Test
    void doesNotResolveHostnames() {
        assertThat(LocalNetworkDiagnostics.isLocalNetworkTarget("server.example.com")).isFalse();
        assertThat(LocalNetworkDiagnostics.isLocalNetworkTarget("localhost")).isFalse();
        assertThat(LocalNetworkDiagnostics.isLocalNetworkTarget("10.211.55.5.example.com")).isFalse();
    }

    @Test
    void offersTheHintOnlyWhenEveryConditionHolds() {
        if (LocalNetworkDiagnostics.isMacOs()) {
            assertWithMessage("macOS + local target + NoRouteToHost should yield the hint")
                .that(LocalNetworkDiagnostics.hintKeyFor(wrappedNoRoute(), "10.211.55.5"))
                .hasValue("terminal.localNetworkPermissionHint");
        } else {
            assertWithMessage("the hint is macOS-only")
                .that(LocalNetworkDiagnostics.hintKeyFor(wrappedNoRoute(), "10.211.55.5")).isEmpty();
        }
        // These two hold on every platform.
        assertThat(LocalNetworkDiagnostics.hintKeyFor(wrappedNoRoute(), "93.184.216.34")).isEmpty();
        assertThat(LocalNetworkDiagnostics.hintKeyFor(new SocketTimeoutException(), "10.211.55.5")).isEmpty();
    }
}
