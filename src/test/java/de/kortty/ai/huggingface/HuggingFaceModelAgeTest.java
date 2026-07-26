package de.kortty.ai.huggingface;

import org.testng.annotations.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

import static com.google.common.truth.Truth.assertThat;

/** Publication age of a Hugging Face repository, the figure the model table shows. */
class HuggingFaceModelAgeTest {

    private static final Instant NOW = Instant.parse("2026-07-26T12:00:00Z");

    private static HuggingFaceModel model(Instant createdAt, Instant lastModified) {
        return new HuggingFaceModel("owner/model", "owner", null, "mit", "test", 4096, 1,
            Set.of("Q4_K_M"), List.of(), Set.of("gguf"), false, false, 0, 0,
            lastModified, createdAt);
    }

    @Test
    void ageIsCountedFromPublicationInWholeDays() {
        assertThat(model(NOW.minus(30, ChronoUnit.DAYS), null).ageInDays(NOW)).isEqualTo(30);
        assertThat(model(NOW.minus(1, ChronoUnit.DAYS), null).ageInDays(NOW)).isEqualTo(1);
        assertThat(model(NOW, null).ageInDays(NOW)).isEqualTo(0);
    }

    /**
     * A README edit bumps lastModified without making the weights newer, so the age must not
     * follow it — the column exists to show the model generation.
     */
    @Test
    void ageIgnoresLaterModifications() {
        HuggingFaceModel old = model(NOW.minus(400, ChronoUnit.DAYS), NOW.minus(1, ChronoUnit.DAYS));
        assertThat(old.ageInDays(NOW)).isEqualTo(400);
    }

    @Test
    void aMissingCreationDateIsReportedAsUnknown() {
        assertThat(model(null, NOW).ageInDays(NOW)).isEqualTo(-1);
        assertThat(model(NOW.minus(5, ChronoUnit.DAYS), null).ageInDays(null)).isEqualTo(-1);
    }

    /** Clock skew between the Hub and this machine must not produce a negative age. */
    @Test
    void aFutureCreationDateIsReportedAsUnknownRatherThanNegative() {
        assertThat(model(NOW.plus(2, ChronoUnit.DAYS), null).ageInDays(NOW)).isEqualTo(-1);
    }
}
