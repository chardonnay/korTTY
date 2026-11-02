package de.kortty.ui;

import de.kortty.memory.external.ExternalMemoryReader;
import org.testng.annotations.Test;

import java.util.Optional;

import static com.google.common.truth.Truth.assertThat;

class ExternalMemoryAddDialogReviewTest {

    @Test
    void theApprovalShowsTheTextThatIsSentNotTheFrontMatterAroundIt() {
        ExternalMemoryReader.Document document = new ExternalMemoryReader.Document(
            "Use strict mode.", "sha", 0, false, Optional.empty(), false, false,
            "---\ndescription: Perl rules\n---\nUse strict mode.");

        assertThat(ExternalMemoryAddDialog.textToReview(document)).isEqualTo("Use strict mode.");
    }

    @Test
    void aFileThatIsNotSentShowsNothingToApprove() {
        ExternalMemoryReader.Document binary = new ExternalMemoryReader.Document(
            "", "sha", 0, false, Optional.empty(), false, true, "");

        assertThat(ExternalMemoryAddDialog.textToReview(binary)).isEmpty();
        assertThat(ExternalMemoryAddDialog.textToReview(null)).isEmpty();
    }
}
