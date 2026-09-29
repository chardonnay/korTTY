package de.kortty.ui;

import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

class InputHardeningSelectorTest {

    @Test
    void restoredByteLimitMapsOntoTheSpinnersWholeMegabytes() {
        assertThat(InputHardeningSelector.maxFileSizeMbFor(10L * 1_048_576L)).isEqualTo(10);
        assertThat(InputHardeningSelector.maxFileSizeMbFor(0L)).isEqualTo(0);
        // Rounded, not truncated: 2.6 MB stored comes back as 3.
        assertThat(InputHardeningSelector.maxFileSizeMbFor(2_726_297L)).isEqualTo(3);
        assertThat(InputHardeningSelector.maxFileSizeMbFor(-5L)).isEqualTo(0);
        assertThat(InputHardeningSelector.maxFileSizeMbFor(Long.MAX_VALUE / 2)).isEqualTo(1024);
    }
}
