package de.kortty.ui;

import static com.google.common.truth.Truth.assertWithMessage;

import com.sithtermfx.core.TtyConnector;
import com.sithtermfx.core.util.PixelSize;
import com.sithtermfx.core.util.TermSize;
import de.kortty.core.SizeRecordingTtyConnector;
import de.kortty.isolation.StrictEscapeFilterTtyConnector;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.testng.annotations.Test;

/**
 * SithTermFX tells the connector the text area's size in pixels through
 * {@code resize(TermSize, PixelSize)}; its default falls back to {@code resize(TermSize)}. A wrapper
 * that only forwards the latter would hand the SSH or local pty 0×0 pixels, and image tools (kitty
 * graphics protocol) could not size their images, so every wrapper korTTY stacks in the main
 * module must forward both overloads.
 */
class ConnectorPixelSizeForwardingTest {

    private static final Map<String, UnaryOperator<TtyConnector>> WRAPPERS = Map.of(
        "shell integration", connector -> new ShellIntegrationTtyConnector(connector, event -> { }),
        "colour filter", connector -> new TerminalView.TerminalColorFilteringTtyConnector(
            connector, () -> true, () -> { }, () -> { }),
        "strict escape filter", StrictEscapeFilterTtyConnector::new);

    @Test
    void everyWrapperForwardsThePixelSize() {
        WRAPPERS.forEach((name, wrap) -> {
            SizeRecordingTtyConnector base = new SizeRecordingTtyConnector();
            TtyConnector wrapped = wrap.apply(base);

            wrapped.resize(new TermSize(100, 30), new PixelSize(900, 540));
            wrapped.resize(new TermSize(80, 24));

            assertWithMessage(name).that(base.resizes()).containsExactly("100x30@900x540px", "80x24").inOrder();
        });
    }
}
