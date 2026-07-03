package de.kortty.plugin.terminaleffects.pack;

import com.sithtermfx.core.TtyConnector;
import com.sithtermfx.core.util.TermSize;
import de.kortty.plugin.terminaleffects.TerminalEffectConnectorWrapper;
import org.jetbrains.annotations.NotNull;
import org.testng.annotations.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.google.common.truth.Truth.assertThat;

public class EffectPackPluginsTest {

    private static final Set<String> ALLOWED_CURSOR_STYLES =
            Set.of("BLOCK", "BLINK_BLOCK", "BLINK_UNDERLINE", "BLINK_BAR");

    private static List<AbstractEffectPackPlugin> allPackPlugins() {
        return List.of(
                new AmberCrt90Plugin(),
                new CommodoreHeritagePlugin(),
                new NeonCityPlugin(),
                new DigitalRainPlugin(),
                new HologramHudPlugin(),
                new PoltergeistPlugin(),
                new Vhs1987Plugin(),
                new SynthwaveHorizonPlugin(),
                new DeepSpaceRadarPlugin(),
                new TypewriterNoirPlugin());
    }

    @Test
    void packPluginsExposeUniqueValidMetadata() {
        Set<String> seenIds = new HashSet<>();
        for (AbstractEffectPackPlugin plugin : allPackPlugins()) {
            assertThat(plugin.id()).matches("[a-z0-9][a-z0-9._-]{0,63}");
            assertThat(seenIds.add(plugin.id())).isTrue();
            assertThat(plugin.displayName()).isNotEmpty();
            assertThat(plugin.description()).isNotEmpty();
            assertThat(plugin.previewLines()).isNotEmpty();
        }
        assertThat(seenIds).hasSize(10);
    }

    @Test
    void packPluginsUseValidAppearances() {
        for (AbstractEffectPackPlugin plugin : allPackPlugins()) {
            var appearance = plugin.appearance();
            assertThat(appearance.fontFamily()).isEqualTo("Monospaced");
            assertThat(appearance.foregroundColor()).matches("#[0-9A-Fa-f]{6}");
            assertThat(appearance.backgroundColor()).matches("#[0-9A-Fa-f]{6}");
            assertThat(appearance.cursorColor()).matches("#[0-9A-Fa-f]{6}");
            assertThat(appearance.cursorStyle()).isIn(ALLOWED_CURSOR_STYLES);
        }
    }

    @Test
    void packPluginsProvidePreviewsWithoutTouchingJavaFx() {
        for (AbstractEffectPackPlugin plugin : allPackPlugins()) {
            assertThat(plugin.createPreview()).isNotNull();
        }
    }

    @Test
    void wrapTypewriterWrapsBaseConnector() {
        FakeTtyConnector base = new FakeTtyConnector();

        TtyConnector wrapped = TypewriterNoirPlugin.wrapTypewriter(base, () -> 1.0);

        assertThat(wrapped).isInstanceOf(PackTypewriterTtyConnector.class);
        assertThat(((PackTypewriterTtyConnector) wrapped).delegate()).isSameInstanceAs(base);
    }

    @Test
    void wrapTypewriterIsIdempotent() {
        FakeTtyConnector base = new FakeTtyConnector();
        TtyConnector wrapped = TypewriterNoirPlugin.wrapTypewriter(base, () -> 1.0);

        assertThat(TypewriterNoirPlugin.wrapTypewriter(wrapped, () -> 1.0)).isSameInstanceAs(wrapped);
    }

    @Test
    void wrapTypewriterUnwrapsForeignEffectWrappers() {
        FakeTtyConnector base = new FakeTtyConnector();
        TtyConnector foreign = new ForeignWrapper(base);

        TtyConnector wrapped = TypewriterNoirPlugin.wrapTypewriter(foreign, () -> 1.0);

        assertThat(wrapped).isInstanceOf(PackTypewriterTtyConnector.class);
        assertThat(((PackTypewriterTtyConnector) wrapped).delegate()).isSameInstanceAs(base);
    }

    private static class FakeTtyConnector implements TtyConnector {

        @Override
        public int read(char[] buf, int offset, int length) {
            return -1;
        }

        @Override
        public void write(byte[] bytes) {
        }

        @Override
        public void write(String string) {
        }

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public void resize(@NotNull TermSize termSize) {
        }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public boolean ready() {
            return false;
        }

        @Override
        public String getName() {
            return "fake";
        }

        @Override
        public void close() {
        }
    }

    private static final class ForeignWrapper extends FakeTtyConnector implements TerminalEffectConnectorWrapper {

        private final TtyConnector delegate;

        private ForeignWrapper(TtyConnector delegate) {
            this.delegate = delegate;
        }

        @Override
        public TtyConnector delegate() {
            return delegate;
        }
    }
}
