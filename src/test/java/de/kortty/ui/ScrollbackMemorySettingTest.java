package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;

import com.sithtermfx.core.model.TerminalTextBuffer;
import com.sithtermfx.ui.settings.DynamicFontSizeSettingsProvider;
import com.sithtermfx.ui.settings.UserSettingsProvider;
import de.kortty.core.ConnectionSettingsSupport;
import de.kortty.model.ConnectionSettings;
import de.kortty.model.GlobalSettings;
import de.kortty.model.ServerConnection;
import de.kortty.ui.ScrollbackTrimTracker.Trim;
import jakarta.xml.bind.JAXBContext;
import java.io.StringReader;
import java.io.StringWriter;
import java.lang.reflect.Constructor;
import org.testng.annotations.Test;

/**
 * Settings → Terminal → Scrollback memory and Compress the scrollback: the stored values, what each
 * pane's provider hands SithTermFX, how a saved change reaches open panes, and that korTTY's
 * line-identity trackers keep counting exactly while lines are compressed or dropped by the limit.
 */
public class ScrollbackMemorySettingTest {

    private static final long MB = 1024L * 1024L;

    private static UserSettingsProvider newProvider(ConnectionSettings settings) throws Exception {
        Class<?> cls = Class.forName("de.kortty.ui.TerminalView$KorTTYSettingsProvider");
        Constructor<?> ctor = cls.getDeclaredConstructor(
                ConnectionSettings.class, DynamicFontSizeSettingsProvider.class, java.util.function.IntSupplier.class);
        ctor.setAccessible(true);
        return (UserSettingsProvider) ctor.newInstance(settings, new DynamicFontSizeSettingsProvider(14f),
                (java.util.function.IntSupplier) () -> 0);
    }

    private static ConnectionSettings unmarshal(String xml) throws Exception {
        return (ConnectionSettings) JAXBContext.newInstance(ConnectionSettings.class)
                .createUnmarshaller().unmarshal(new StringReader(xml));
    }

    private static EmulatorTextBufferFixture terminalWithLines(int lines) {
        EmulatorTextBufferFixture fixture = new EmulatorTextBufferFixture(80, 24, 100_000, uri -> null);
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < lines; i++) {
            text.append(String.format("%06d: compiling module %d of the build, nothing to see here%n", i, i % 41))
                    .append('\r');
        }
        fixture.write(text.toString());
        return fixture;
    }

    @Test
    public void aFreshSettingsObjectAllowsTenMegabytesAndCompresses() throws Exception {
        ConnectionSettings settings = new ConnectionSettings();

        assertThat(settings.getScrollbackMemoryMb()).isEqualTo(10);
        assertThat(settings.isScrollbackCompression()).isTrue();
        UserSettingsProvider provider = newProvider(settings);
        assertThat(provider.getBufferMaxMemoryBytes()).isEqualTo(10 * MB);
        assertThat(provider.isScrollbackCompressionEnabled()).isTrue();
    }

    @Test
    public void theProviderFollowsTheSettings() throws Exception {
        ConnectionSettings settings = new ConnectionSettings();
        settings.setScrollbackMemoryMb(50);
        settings.setScrollbackCompression(false);

        UserSettingsProvider provider = newProvider(settings);

        assertThat(provider.getBufferMaxMemoryBytes()).isEqualTo(50 * MB);
        assertThat(provider.isScrollbackCompressionEnabled()).isFalse();
    }

    @Test
    public void valuesOutsideTheSliderRangeAreClamped() {
        ConnectionSettings settings = new ConnectionSettings();
        settings.setScrollbackMemoryMb(500);
        assertThat(settings.getScrollbackMemoryMb()).isEqualTo(50);
        settings.setScrollbackMemoryMb(1);
        assertThat(settings.getScrollbackMemoryMb()).isEqualTo(1);
        // 0 or less is a corrupt file, not a wish for the smallest limit.
        settings.setScrollbackMemoryMb(0);
        assertThat(settings.getScrollbackMemoryMb()).isEqualTo(10);
        settings.setScrollbackMemoryMb(-7);
        assertThat(settings.getScrollbackMemoryMb()).isEqualTo(10);
    }

    @Test
    public void anOldSettingsFileLoadsTheDefaults() throws Exception {
        ConnectionSettings old = unmarshal("<settings><scrollbackLines>20000</scrollbackLines></settings>");

        assertThat(old.getScrollbackLines()).isEqualTo(20_000);
        assertThat(old.getScrollbackMemoryMb()).isEqualTo(10);
        assertThat(old.isScrollbackCompression()).isTrue();
        assertThat(unmarshal("<settings><scrollbackMemoryMb>999</scrollbackMemoryMb></settings>")
                .getScrollbackMemoryMb()).isEqualTo(50);
    }

    @Test
    public void bothValuesSurviveASaveAndReload() throws Exception {
        ConnectionSettings settings = new ConnectionSettings();
        settings.setScrollbackMemoryMb(25);
        settings.setScrollbackCompression(false);
        StringWriter xml = new StringWriter();
        JAXBContext.newInstance(ConnectionSettings.class).createMarshaller().marshal(settings, xml);

        assertThat(xml.toString()).contains("<scrollbackMemoryMb>25</scrollbackMemoryMb>");
        assertThat(xml.toString()).contains("<scrollbackCompression>false</scrollbackCompression>");
        ConnectionSettings reloaded = unmarshal(xml.toString());
        assertThat(reloaded.getScrollbackMemoryMb()).isEqualTo(25);
        assertThat(reloaded.isScrollbackCompression()).isFalse();
    }

    @Test
    public void aConnectionWithOwnSettingsKeepsTheGlobalScrollbackMemory() {
        ConnectionSettings global = new ConnectionSettings();
        global.setScrollbackMemoryMb(40);
        global.setScrollbackCompression(false);
        GlobalSettings globalSettings = new GlobalSettings();
        globalSettings.setDefaultTerminalSettings(global);
        ServerConnection connection = new ServerConnection();
        connection.setSettings(ConnectionSettingsSupport.settingsToSave(
                true, connection.getSettings(), globalSettings.getDefaultTerminalSettings(), s -> s.setFontSize(20)));

        ConnectionSettings effective = ConnectionSettingsSupport.effectiveTerminalSettings(connection, globalSettings);

        assertThat(effective.getFontSize()).isEqualTo(20);
        assertThat(effective.getScrollbackMemoryMb()).isEqualTo(40);
        assertThat(effective.isScrollbackCompression()).isFalse();
    }

    @Test
    public void aSavedChangeReachesAnOpenPane() {
        EmulatorTextBufferFixture fixture = terminalWithLines(20_000);
        TerminalTextBuffer buffer = fixture.buffer;
        ConnectionSettings settings = new ConnectionSettings();
        settings.setScrollbackCompression(false);
        TerminalView.applyScrollbackMemory(buffer, settings);
        int before = buffer.getHistoryLinesCount();
        assertThat(buffer.getHistoryMemoryLimit()).isEqualTo(10 * MB);

        settings.setScrollbackMemoryMb(1);
        TerminalView.applyScrollbackMemory(buffer, settings);

        assertThat(buffer.getHistoryMemoryLimit()).isEqualTo(MB);
        assertThat(buffer.getHistoryMemoryUsage()).isAtMost(MB);
        assertThat(buffer.getHistoryLinesCount()).isLessThan(before);
        assertThat(buffer.getLine(-1).getText()).startsWith(String.format("%06d:", 20_000 - 24));

        settings.setScrollbackCompression(true);
        TerminalView.applyScrollbackMemory(buffer, settings);
        try {
            assertThat(buffer.isScrollbackCompressionEnabled()).isTrue();
            assertThat(buffer.compressHistoryNow()).isGreaterThan(0);
            assertThat(buffer.getHistoryMemoryUsage()).isLessThan(MB / 3);
        } finally {
            settings.setScrollbackCompression(false);
            TerminalView.applyScrollbackMemory(buffer, settings);
        }
        assertThat(buffer.isScrollbackCompressionEnabled()).isFalse();
    }

    @Test
    public void compressionKeepsTheLineAnchorsOfTimestampsAndMarks() {
        EmulatorTextBufferFixture fixture = terminalWithLines(3_000);
        TerminalTextBuffer buffer = fixture.buffer;
        ScrollbackTrimTracker tracker = ScrollbackTrimTracker.forBuffer(buffer);
        buffer.addModelListener(tracker::observe);
        tracker.poll();
        buffer.setScrollbackCompressionEnabled(true);
        try {
            assertThat(buffer.compressHistoryNow()).isGreaterThan(2_000);
            // Packing keeps every line object, so nothing looks moved or rebuilt.
            assertThat(tracker.poll()).isEqualTo(Trim.none());

            // A limit that drops lines is a plain shift, as the line limit is.
            int before = buffer.getHistoryLinesCount();
            buffer.setHistoryMemoryLimit(50_000);
            int dropped = before - buffer.getHistoryLinesCount();
            assertThat(dropped).isGreaterThan(0);
            assertThat(tracker.poll()).isEqualTo(Trim.shift(dropped));
            String first = buffer.getLine(-buffer.getHistoryLinesCount()).getText();
            assertThat(first).startsWith(String.format("%06d:", 3_000 - 24 + 1 - buffer.getHistoryLinesCount()));
        } finally {
            buffer.setScrollbackCompressionEnabled(false);
        }
    }
}
