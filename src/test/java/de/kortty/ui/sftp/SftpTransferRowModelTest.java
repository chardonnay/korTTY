package de.kortty.ui.sftp;

import de.kortty.core.sftp.transfer.TransferState;
import de.kortty.ui.I18n;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Properties;

import static com.google.common.truth.Truth.assertThat;

/** What the transfer list shows, headless. */
class SftpTransferRowModelTest {

    @Test
    void sizesUseBinaryUnitsWithOneDecimal() {
        assertThat(SftpTransferRowModel.size(0, Locale.ROOT)).isEqualTo("0 B");
        assertThat(SftpTransferRowModel.size(1023, Locale.ROOT)).isEqualTo("1023 B");
        assertThat(SftpTransferRowModel.size(1536, Locale.ROOT)).isEqualTo("1.5 KB");
        assertThat(SftpTransferRowModel.size(5L * 1024 * 1024, Locale.ROOT)).isEqualTo("5.0 MB");
        assertThat(SftpTransferRowModel.size(3L * 1024 * 1024 * 1024 / 2, Locale.ROOT)).isEqualTo("1.5 GB");
        assertThat(SftpTransferRowModel.size(2L << 40, Locale.ROOT)).isEqualTo("2.0 TB");
        assertThat(SftpTransferRowModel.size(1536, Locale.GERMANY)).isEqualTo("1,5 KB");
    }

    @Test
    void anUnknownSizeShowsADashAndOnlyTheBytesSoFar() {
        assertThat(SftpTransferRowModel.size(-1, Locale.ROOT)).isEqualTo("—");
        assertThat(SftpTransferRowModel.bytesText(2048, -1, Locale.ROOT)).isEqualTo("2.0 KB");
        assertThat(SftpTransferRowModel.bytesText(1024, 4096, Locale.ROOT))
            .isEqualTo(I18n.get("sftp.queue.bytesOf", "1.0 KB", "4.0 KB"));
        assertThat(SftpTransferRowModel.bytesText(1024, 4096, Locale.ROOT)).contains("4.0 KB");
        assertThat(SftpTransferRowModel.progress(TransferState.RUNNING, 10, -1)).isEqualTo(-1.0);
        assertThat(SftpTransferRowModel.progress(TransferState.QUEUED, 0, -1)).isEqualTo(0.0);
        assertThat(SftpTransferRowModel.progress(TransferState.RUNNING, 512, 1024)).isEqualTo(0.5);
        assertThat(SftpTransferRowModel.progress(TransferState.DONE, 0, 0)).isEqualTo(1.0);
        assertThat(SftpTransferRowModel.progress(TransferState.RUNNING, 2048, 1024)).isEqualTo(1.0);
    }

    @Test
    void speedIsBytesPerSecondAndEmptyWhenNothingMoves() {
        assertThat(SftpTransferRowModel.speed(0, Locale.ROOT)).isEmpty();
        assertThat(SftpTransferRowModel.speed(Double.NaN, Locale.ROOT)).isEmpty();
        assertThat(SftpTransferRowModel.speed(1.5 * 1024 * 1024, Locale.ROOT))
            .isEqualTo(I18n.get("sftp.queue.speed", "1.5 MB"));
        assertThat(SftpTransferRowModel.speed(1.5 * 1024 * 1024, Locale.ROOT)).contains("1.5 MB");
    }

    @Test
    void etaIsRoundedToWhatAPersonReads() throws IOException {
        assertThat(SftpTransferRowModel.eta(null)).isEmpty();
        assertThat(SftpTransferRowModel.eta(Duration.ofSeconds(20)))
            .isEqualTo(I18n.get("sftp.queue.eta.lessThanMinute"));
        assertThat(SftpTransferRowModel.eta(Duration.ofSeconds(170)))
            .isEqualTo(I18n.get("sftp.queue.eta.minutes", "3"));
        assertThat(SftpTransferRowModel.eta(Duration.ofMinutes(65)))
            .isEqualTo(I18n.get("sftp.queue.eta.hours", "1", "5"));

        // In English: "about 3 min".
        Properties english = new Properties();
        try (Reader reader = Files.newBufferedReader(Path.of("src/main/resources/i18n/messages.properties"),
                StandardCharsets.UTF_8)) {
            english.load(reader);
        }
        assertThat(english.getProperty("sftp.queue.eta.minutes").replace("{0}", "3")).isEqualTo("about 3 min");
    }

    @Test
    void statusIsEmptyWhenNothingRuns() {
        assertThat(SftpTransferRowModel.status(new SftpTransferRowModel.Totals(3, 3, 0, 0), Locale.ROOT)).isNull();
        assertThat(SftpTransferRowModel.status(new SftpTransferRowModel.Totals(1, 3, 2, 0), Locale.ROOT))
            .isEqualTo(I18n.get("sftp.queue.status.active", "1", "3"));
        assertThat(SftpTransferRowModel.status(new SftpTransferRowModel.Totals(1, 3, 2, 2048), Locale.ROOT))
            .isEqualTo(I18n.get("sftp.queue.status.activeSpeed", "1", "3", I18n.get("sftp.queue.speed", "2.0 KB")));
    }

    @Test
    void everyStateHasALabel() {
        for (TransferState state : TransferState.values()) {
            assertThat(SftpTransferRowModel.state(state, false)).isNotEmpty();
            assertThat(SftpTransferRowModel.state(state, false)).doesNotContain("sftp.queue");
        }
        assertThat(SftpTransferRowModel.state(TransferState.RUNNING, true))
            .isEqualTo(I18n.get("sftp.queue.state.resumed"));
    }
}
