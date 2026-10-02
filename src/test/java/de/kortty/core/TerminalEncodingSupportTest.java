package de.kortty.core;

import de.kortty.model.ConnectionProtocol;
import de.kortty.model.ConnectionSettings;
import de.kortty.model.GlobalSettings;
import de.kortty.model.ServerConnection;
import org.testng.annotations.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.google.common.truth.Truth.assertThat;

class TerminalEncodingSupportTest {

    private static final Charset ISO_8859_15 = Charset.forName("ISO-8859-15");
    private static final Charset WINDOWS_1252 = Charset.forName("Windows-1252");

    @Test
    void theConnectionOverrideWinsOverTheGlobalEncoding() {
        ServerConnection connection = connection(ConnectionProtocol.SSH_TCP, "ISO-8859-1");

        assertThat(TerminalEncodingSupport.resolve(connection, "Windows-1252"))
            .isEqualTo(StandardCharsets.ISO_8859_1);
        assertThat(TerminalEncodingSupport.resolve(connection, confirmedGlobal("Windows-1252")))
            .isEqualTo(StandardCharsets.ISO_8859_1);
    }

    @Test
    void withoutAnOverrideTheGlobalEncodingApplies() {
        assertThat(TerminalEncodingSupport.resolve(connection(ConnectionProtocol.SSH_TCP, null), "ISO-8859-15"))
            .isEqualTo(ISO_8859_15);
        ServerConnection blank = connection(ConnectionProtocol.SSH_TCP, null);
        blank.setEncoding("   ");
        assertThat(blank.getEncoding()).isNull();
        assertThat(TerminalEncodingSupport.resolve(blank, confirmedGlobal("Windows-1252")))
            .isEqualTo(WINDOWS_1252);
    }

    @Test
    void withoutAnyEncodingTheSessionIsUtf8() {
        assertThat(TerminalEncodingSupport.resolve(connection(ConnectionProtocol.SSH_TCP, null), (String) null))
            .isEqualTo(StandardCharsets.UTF_8);
        assertThat(TerminalEncodingSupport.resolve(connection(ConnectionProtocol.SSH_TCP, null), (GlobalSettings) null))
            .isEqualTo(StandardCharsets.UTF_8);
        assertThat(TerminalEncodingSupport.resolve(null, (String) null)).isEqualTo(StandardCharsets.UTF_8);
    }

    @Test
    void unknownAndMalformedNamesFallBackWithoutThrowing() {
        for (String bad : new String[] {"KOI9-X", "not a charset!", "KOI8-R", "Shift_JIS"}) {
            ServerConnection connection = connection(ConnectionProtocol.SSH_TCP, bad);
            assertThat(TerminalEncodingSupport.parse(bad)).isNull();
            // An unusable override is ignored: the global value applies, then UTF-8.
            assertThat(TerminalEncodingSupport.resolve(connection, (String) null)).isEqualTo(StandardCharsets.UTF_8);
            assertThat(TerminalEncodingSupport.resolve(connection, "ISO-8859-1")).isEqualTo(StandardCharsets.ISO_8859_1);
            assertThat(TerminalEncodingSupport.resolve(connection(ConnectionProtocol.SSH_TCP, null), bad))
                .isEqualTo(StandardCharsets.UTF_8);
        }
    }

    @Test
    void moshIsAlwaysUtf8() {
        for (ConnectionProtocol mosh : new ConnectionProtocol[] {ConnectionProtocol.MOSH, ConnectionProtocol.MOSH_CLIENT}) {
            assertThat(TerminalEncodingSupport.isUtf8Only(mosh)).isTrue();
            assertThat(TerminalEncodingSupport.resolve(connection(mosh, "ISO-8859-1"), confirmedGlobal("Windows-1252")))
                .isEqualTo(StandardCharsets.UTF_8);
        }
        assertThat(TerminalEncodingSupport.isUtf8Only(ConnectionProtocol.SSH_TCP)).isFalse();
        assertThat(TerminalEncodingSupport.isUtf8Only(ConnectionProtocol.LOCAL_SHELL)).isFalse();
        assertThat(TerminalEncodingSupport.isUtf8Only(null)).isFalse();
    }

    @Test
    void aLocalShellIgnoresTheGlobalEncodingButHonoursItsOwn() {
        assertThat(TerminalEncodingSupport.resolve(connection(ConnectionProtocol.LOCAL_SHELL, null),
            confirmedGlobal("ISO-8859-1"))).isEqualTo(StandardCharsets.UTF_8);
        assertThat(TerminalEncodingSupport.resolve(connection(ConnectionProtocol.LOCAL_SHELL, "Windows-1252"),
            confirmedGlobal("ISO-8859-1"))).isEqualTo(WINDOWS_1252);
    }

    @Test
    void aGlobalValueStoredBeforeTheSettingWorkedWaitsForTheTerminalPageToBeSaved() {
        GlobalSettings settings = new GlobalSettings();
        settings.getDefaultTerminalSettings().setEncoding("ISO-8859-1");
        ServerConnection ssh = connection(ConnectionProtocol.SSH_TCP, null);

        assertThat(settings.isTerminalEncodingConfirmed()).isFalse();
        assertThat(TerminalEncodingSupport.globalEncoding(settings)).isNull();
        assertThat(TerminalEncodingSupport.isGlobalEncodingPending(settings)).isTrue();
        assertThat(TerminalEncodingSupport.resolve(ssh, settings)).isEqualTo(StandardCharsets.UTF_8);
        // The per-connection override is new, so it never needs the guard.
        assertThat(TerminalEncodingSupport.resolve(connection(ConnectionProtocol.SSH_TCP, "ISO-8859-15"), settings))
            .isEqualTo(ISO_8859_15);

        // Saving the dialog without having opened the Terminal page changes nothing.
        TerminalEncodingSupport.confirmOnSave(settings, false);
        assertThat(TerminalEncodingSupport.resolve(ssh, settings)).isEqualTo(StandardCharsets.UTF_8);

        TerminalEncodingSupport.confirmOnSave(settings, true);
        assertThat(settings.isTerminalEncodingConfirmed()).isTrue();
        assertThat(TerminalEncodingSupport.isGlobalEncodingPending(settings)).isFalse();
        assertThat(TerminalEncodingSupport.resolve(ssh, settings)).isEqualTo(StandardCharsets.ISO_8859_1);

        TerminalEncodingSupport.confirmOnSave(null, true); // no settings: nothing to confirm, no exception
    }

    @Test
    void aStoredUtf8ValueIsNeverPending() {
        GlobalSettings settings = new GlobalSettings();
        settings.getDefaultTerminalSettings().setEncoding("UTF-8");
        assertThat(TerminalEncodingSupport.isGlobalEncodingPending(settings)).isFalse();
        assertThat(TerminalEncodingSupport.isGlobalEncodingPending(null)).isFalse();
    }

    @Test
    void theConfirmationIsPersistedAndAnOlderSettingsFileReadsAsUnconfirmed() throws Exception {
        Path dir = Files.createTempDirectory("kortty-terminal-encoding-confirmed");
        Path file = dir.resolve("global-settings.xml");
        try {
            // Written by a version in which the setting had no effect: no confirmation element.
            Files.writeString(file, """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <globalSettings>
                    <defaultTerminalSettings>
                        <encoding>Windows-1252</encoding>
                    </defaultTerminalSettings>
                </globalSettings>
                """);
            GlobalSettingsManager legacy = new GlobalSettingsManager(dir);
            legacy.load();
            assertThat(legacy.getSettings().getDefaultTerminalSettings().getEncoding()).isEqualTo("Windows-1252");
            assertThat(legacy.getSettings().isTerminalEncodingConfirmed()).isFalse();
            assertThat(TerminalEncodingSupport.resolve(connection(ConnectionProtocol.SSH_TCP, null), legacy.getSettings()))
                .isEqualTo(StandardCharsets.UTF_8);

            TerminalEncodingSupport.confirmOnSave(legacy.getSettings(), true);
            legacy.save();

            GlobalSettingsManager reloaded = new GlobalSettingsManager(dir);
            reloaded.load();
            assertThat(reloaded.getSettings().isTerminalEncodingConfirmed()).isTrue();
            assertThat(TerminalEncodingSupport.resolve(connection(ConnectionProtocol.SSH_TCP, null), reloaded.getSettings()))
                .isEqualTo(WINDOWS_1252);
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void withoutAnApplicationOnlyTheOverrideCounts() {
        assertThat(TerminalEncodingSupport.resolveFromSettings(connection(ConnectionProtocol.SSH_TCP, "ISO-8859-1")))
            .isEqualTo(StandardCharsets.ISO_8859_1);
        assertThat(TerminalEncodingSupport.resolveFromSettings(connection(ConnectionProtocol.SSH_TCP, null)))
            .isEqualTo(StandardCharsets.UTF_8);
        assertThat(TerminalEncodingSupport.resolveFromSettings(null)).isEqualTo(StandardCharsets.UTF_8);
    }

    @Test
    void everyOfferedEncodingIsAvailableAndParses() {
        assertThat(TerminalEncodingSupport.SUPPORTED_ENCODINGS)
            .containsExactly("UTF-8", "ISO-8859-1", "ISO-8859-15", "Windows-1252").inOrder();
        for (String name : TerminalEncodingSupport.SUPPORTED_ENCODINGS) {
            assertThat(Charset.isSupported(name)).isTrue();
            assertThat(TerminalEncodingSupport.parse(name)).isEqualTo(Charset.forName(name));
            assertThat(TerminalEncodingSupport.displayName(name)).isEqualTo(name);
        }
        assertThat(TerminalEncodingSupport.parse(" utf-8 ")).isEqualTo(StandardCharsets.UTF_8);
        assertThat(TerminalEncodingSupport.parse("latin1")).isEqualTo(StandardCharsets.ISO_8859_1);
        assertThat(TerminalEncodingSupport.parse("cp1252")).isEqualTo(WINDOWS_1252);
        assertThat(TerminalEncodingSupport.parse(null)).isNull();
        assertThat(TerminalEncodingSupport.parse("")).isNull();
    }

    @Test
    void thePickerShowsAliasesAsTheirEntryAndKeepsAnUnknownStoredValue() {
        assertThat(TerminalEncodingSupport.displayName("latin1")).isEqualTo("ISO-8859-1");
        assertThat(TerminalEncodingSupport.displayName("windows-1252")).isEqualTo("Windows-1252");
        assertThat(TerminalEncodingSupport.displayName(" ")).isNull();
        assertThat(TerminalEncodingSupport.offeredEncodings(null)).isEqualTo(TerminalEncodingSupport.SUPPORTED_ENCODINGS);
        assertThat(TerminalEncodingSupport.offeredEncodings("latin1")).isEqualTo(TerminalEncodingSupport.SUPPORTED_ENCODINGS);
        assertThat(TerminalEncodingSupport.offeredEncodings("KOI8-R"))
            .containsExactly("UTF-8", "ISO-8859-1", "ISO-8859-15", "Windows-1252", "KOI8-R").inOrder();
    }

    @Test
    void onlyTheLegacyEncodingsAreSingleByte() {
        assertThat(TerminalEncodingSupport.isSingleByte(StandardCharsets.ISO_8859_1)).isTrue();
        assertThat(TerminalEncodingSupport.isSingleByte(ISO_8859_15)).isTrue();
        assertThat(TerminalEncodingSupport.isSingleByte(WINDOWS_1252)).isTrue();
        assertThat(TerminalEncodingSupport.isSingleByte(StandardCharsets.UTF_8)).isFalse();
        assertThat(TerminalEncodingSupport.isSingleByte(null)).isFalse();
    }

    private static ServerConnection connection(ConnectionProtocol protocol, String encoding) {
        ServerConnection connection = new ServerConnection("test", "example.com", 22, "tester");
        connection.setProtocol(protocol);
        connection.setEncoding(encoding);
        return connection;
    }

    private static GlobalSettings confirmedGlobal(String encoding) {
        GlobalSettings settings = new GlobalSettings();
        ConnectionSettings defaults = new ConnectionSettings();
        defaults.setEncoding(encoding);
        settings.setDefaultTerminalSettings(defaults);
        settings.setTerminalEncodingConfirmed(true);
        return settings;
    }
}
