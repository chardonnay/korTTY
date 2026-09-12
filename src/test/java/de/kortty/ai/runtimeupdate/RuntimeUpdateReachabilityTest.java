package de.kortty.ai.runtimeupdate;

import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import javax.net.ssl.SSLHandshakeException;
import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

class RuntimeUpdateReachabilityTest {

    @Test
    void recognisesTransportFailuresAnywhereInTheCauseChain() {
        assertThat(RuntimeUpdateReachability.isUnreachable(
            new HttpConnectTimeoutException("HTTP connect timed out"))).isTrue();
        assertThat(RuntimeUpdateReachability.isUnreachable(
            new IOException("index unavailable", new UnknownHostException("releases.kortty.de"))))
            .isTrue();
        assertThat(RuntimeUpdateReachability.isUnreachable(
            new IOException("wrapped", new IOException("deeper", new ConnectException("refused")))))
            .isTrue();
    }

    @Test
    void keepsServerSideAndTlsFailuresReportable() {
        // The server answered, or answered suspiciously — neither is "we could not get there".
        assertThat(RuntimeUpdateReachability.isUnreachable(
            new IOException("MLX runtime index request failed with HTTP 503."))).isFalse();
        assertThat(RuntimeUpdateReachability.isUnreachable(
            new IOException("MLX runtime index signature verification failed."))).isFalse();
        assertThat(RuntimeUpdateReachability.isUnreachable(
            new SSLHandshakeException("PKIX path building failed"))).isFalse();
        assertThat(RuntimeUpdateReachability.isUnreachable(null)).isFalse();
    }

    @Test
    void survivesASelfReferentialCauseChain() {
        IOException failure = new IOException("odd") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };

        assertThat(RuntimeUpdateReachability.isUnreachable(failure)).isFalse();
    }
}
