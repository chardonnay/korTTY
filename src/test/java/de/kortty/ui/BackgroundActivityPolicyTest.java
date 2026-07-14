package de.kortty.ui;

import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

class BackgroundActivityPolicyTest {

    @Test
    void foregroundPollingOnlyRunsForFocusedVisibleWindow() {
        assertThat(MainWindow.shouldRunForegroundPolling(true, false, true)).isTrue();
        assertThat(MainWindow.shouldRunForegroundPolling(true, false, false)).isFalse();
        assertThat(MainWindow.shouldRunForegroundPolling(true, true, true)).isFalse();
        assertThat(MainWindow.shouldRunForegroundPolling(false, false, true)).isFalse();
    }

    @Test
    void designAnimationUsesSameForegroundPolicy() {
        assertThat(AppDesignAnimator.shouldAnimateWindow(true, true, false)).isTrue();
        assertThat(AppDesignAnimator.shouldAnimateWindow(true, false, false)).isFalse();
        assertThat(AppDesignAnimator.shouldAnimateWindow(true, true, true)).isFalse();
        assertThat(AppDesignAnimator.shouldAnimateWindow(false, true, false)).isFalse();
    }
}
