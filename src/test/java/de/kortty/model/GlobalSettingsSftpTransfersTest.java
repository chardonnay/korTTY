package de.kortty.model;

import jakarta.xml.bind.JAXBContext;
import org.testng.annotations.Test;

import java.io.StringReader;
import java.io.StringWriter;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * The SFTP transfer settings in {@code global-settings.xml}: parallel transfers, the conflict
 * default, resume and keeping a cancelled transfer's partial file. They survive the round trip, and
 * a settings file from before they existed gets the defaults (3, ask, resume on, keep off).
 */
class GlobalSettingsSftpTransfersTest {

    @Test
    void aSettingsFileFromBeforeTheTransferSettingsGetsTheDefaults() throws Exception {
        GlobalSettings settings = unmarshal("<globalSettings><sftpAutoCloseMinutes>5</sftpAutoCloseMinutes>"
            + "</globalSettings>");

        assertThat(settings.getSftpParallelTransfers()).isEqualTo(3);
        assertThat(settings.getSftpConflictDefault()).isEqualTo(SftpConflictDefault.ASK);
        assertThat(settings.isSftpResumePartialTransfers()).isTrue();
        assertThat(settings.isSftpKeepPartialOnCancel()).isFalse();
        GlobalSettings fresh = new GlobalSettings();
        assertThat(fresh.getSftpParallelTransfers()).isEqualTo(GlobalSettings.DEFAULT_SFTP_PARALLEL_TRANSFERS);
        assertThat(fresh.getSftpConflictDefault()).isEqualTo(SftpConflictDefault.DEFAULT);
    }

    @Test
    void theTransferSettingsSurviveTheRoundTrip() throws Exception {
        GlobalSettings settings = new GlobalSettings();
        settings.setSftpParallelTransfers(5);
        settings.setSftpConflictDefault(SftpConflictDefault.SKIP);
        settings.setSftpResumePartialTransfers(false);
        settings.setSftpKeepPartialOnCancel(true);

        String xml = marshal(settings);
        GlobalSettings restored = unmarshal(xml);

        assertThat(xml).contains("<sftpParallelTransfers>5</sftpParallelTransfers>");
        assertThat(xml).contains("<sftpConflictDefault>skip</sftpConflictDefault>");
        assertThat(xml).contains("<sftpResumePartialTransfers>false</sftpResumePartialTransfers>");
        assertThat(xml).contains("<sftpKeepPartialOnCancel>true</sftpKeepPartialOnCancel>");
        assertThat(restored.getSftpParallelTransfers()).isEqualTo(5);
        assertThat(restored.getSftpConflictDefault()).isEqualTo(SftpConflictDefault.SKIP);
        assertThat(restored.isSftpResumePartialTransfers()).isFalse();
        assertThat(restored.isSftpKeepPartialOnCancel()).isTrue();
    }

    @Test
    void handEditedValuesAreClampedOrFallBackToAsk() throws Exception {
        GlobalSettings settings = unmarshal("<globalSettings><sftpParallelTransfers>99</sftpParallelTransfers>"
            + "<sftpConflictDefault>destroy</sftpConflictDefault></globalSettings>");

        assertThat(settings.getSftpParallelTransfers()).isEqualTo(GlobalSettings.MAX_SFTP_PARALLEL_TRANSFERS);
        assertWithMessage("an unknown value must never make transfers replace files without asking")
            .that(settings.getSftpConflictDefault()).isEqualTo(SftpConflictDefault.ASK);
        settings.setSftpParallelTransfers(-3);
        assertThat(settings.getSftpParallelTransfers()).isEqualTo(GlobalSettings.MIN_SFTP_PARALLEL_TRANSFERS);
        settings.setSftpConflictDefault(null);
        assertThat(settings.getSftpConflictDefault()).isEqualTo(SftpConflictDefault.ASK);
        assertThat(SftpConflictDefault.parseId(" OVERWRITE ")).isEqualTo(SftpConflictDefault.OVERWRITE);
        assertThat(SftpConflictDefault.parseId("")).isNull();
    }

    private static String marshal(GlobalSettings settings) throws Exception {
        StringWriter writer = new StringWriter();
        JAXBContext.newInstance(GlobalSettings.class).createMarshaller().marshal(settings, writer);
        return writer.toString();
    }

    @Test
    void theExternalEditorCommandSurvivesTheRoundTripAndBlankStoresNone() throws Exception {
        GlobalSettings settings = new GlobalSettings();
        assertThat(settings.getSftpExternalEditorCommand()).isEmpty();
        settings.setSftpExternalEditorCommand("  code --wait {file} ");

        String xml = marshal(settings);
        GlobalSettings restored = unmarshal(xml);

        assertThat(restored.getSftpExternalEditorCommand()).isEqualTo("code --wait {file}");
        restored.setSftpExternalEditorCommand("   ");
        assertThat(marshal(restored)).doesNotContain("sftpExternalEditorCommand");
        assertThat(restored.getSftpExternalEditorCommand()).isEmpty();
    }

    private static GlobalSettings unmarshal(String xml) throws Exception {
        return (GlobalSettings) JAXBContext.newInstance(GlobalSettings.class)
                .createUnmarshaller().unmarshal(new StringReader(xml));
    }
}
