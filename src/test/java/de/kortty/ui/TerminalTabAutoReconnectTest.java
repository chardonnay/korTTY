package de.kortty.ui;

import de.kortty.model.GlobalSettings;
import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

/**
 * Pins the auto-reconnect backoff: quick first retry, growing delays, capped at one minute so a
 * long outage does not hammer the server.
 */
class TerminalTabAutoReconnectTest {

    @Test
    void backoffGrowsAndIsCappedAtOneMinute() {
        assertThat(TerminalTab.autoReconnectDelaySeconds(0)).isEqualTo(3);
        assertThat(TerminalTab.autoReconnectDelaySeconds(1)).isEqualTo(5);
        assertThat(TerminalTab.autoReconnectDelaySeconds(2)).isEqualTo(10);
        assertThat(TerminalTab.autoReconnectDelaySeconds(3)).isEqualTo(20);
        assertThat(TerminalTab.autoReconnectDelaySeconds(4)).isEqualTo(30);
        assertThat(TerminalTab.autoReconnectDelaySeconds(5)).isEqualTo(60);
        assertThat(TerminalTab.autoReconnectDelaySeconds(50)).isEqualTo(60);
    }

    @Test
    void negativeAttemptFallsBackToFirstDelay() {
        assertThat(TerminalTab.autoReconnectDelaySeconds(-1)).isEqualTo(3);
    }

    @Test
    void autoReconnectIsOffByDefault() {
        assertThat(new GlobalSettings().isAutoReconnectEnabled()).isFalse();
    }

    @Test
    void autoReconnectSettingRoundTrips() {
        GlobalSettings settings = new GlobalSettings();
        settings.setAutoReconnectEnabled(true);
        assertThat(settings.isAutoReconnectEnabled()).isTrue();
    }
}
