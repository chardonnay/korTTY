package de.kortty.core;

import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

class AiFileAttachmentTest {

    @Test
    void fitsWithinCountsSelectionAndFileTogether() {
        AiFileAttachment attachment = new AiFileAttachment("a.txt", "/tmp/a.txt", "12345");

        assertThat(attachment.length()).isEqualTo(5);
        assertThat(attachment.fitsWithin("abc", 8)).isTrue();
        assertThat(attachment.fitsWithin("abc", 7)).isFalse();
        assertThat(attachment.fitsWithin(null, 5)).isTrue();
    }

    @Test
    void nullPathFallsBackToFileNameAndNullContentToEmpty() {
        AiFileAttachment attachment = new AiFileAttachment("a.txt", null, null);

        assertThat(attachment.sourcePath()).isEqualTo("a.txt");
        assertThat(attachment.content()).isEmpty();
        assertThat(attachment.fitsWithin("", 0)).isTrue();
    }
}
