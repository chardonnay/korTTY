package de.kortty.ui;

import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

class AppDesignNavigationTest {

    @Test
    void nextStepsForwardThroughTheList() {
        assertThat(AppDesignNavigation.next(0, 5)).isEqualTo(1);
        assertThat(AppDesignNavigation.next(3, 5)).isEqualTo(4);
    }

    @Test
    void nextWrapsFromLastToFirst() {
        assertThat(AppDesignNavigation.next(4, 5)).isEqualTo(0);
    }

    @Test
    void previousStepsBackwardThroughTheList() {
        assertThat(AppDesignNavigation.previous(4, 5)).isEqualTo(3);
        assertThat(AppDesignNavigation.previous(1, 5)).isEqualTo(0);
    }

    @Test
    void previousWrapsFromFirstToLast() {
        assertThat(AppDesignNavigation.previous(0, 5)).isEqualTo(4);
    }

    @Test
    void degenerateCountsAndIndicesClampToZero() {
        assertThat(AppDesignNavigation.next(0, 0)).isEqualTo(0);
        assertThat(AppDesignNavigation.previous(0, 0)).isEqualTo(0);
        assertThat(AppDesignNavigation.next(2, -1)).isEqualTo(0);
        // Defensive against an unexpected negative/no selection.
        assertThat(AppDesignNavigation.next(-1, 5)).isEqualTo(0);
        assertThat(AppDesignNavigation.previous(-1, 5)).isEqualTo(0);
    }
}
