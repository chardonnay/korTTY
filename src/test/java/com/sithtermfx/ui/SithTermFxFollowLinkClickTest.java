package com.sithtermfx.ui;

import com.sithtermfx.core.HyperlinkStyle;
import com.sithtermfx.core.TextStyle;
import com.sithtermfx.core.model.hyperlinks.LinkInfo;
import com.sithtermfx.ui.settings.DefaultSettingsProvider;
import com.sithtermfx.ui.settings.ModifierKeys;
import com.sithtermfx.ui.settings.SettingsProvider;
import de.kortty.ui.TerminalLinkClickPolicy;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import org.jetbrains.annotations.NotNull;
import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertWithMessage;

/**
 * Pins how SithTermFX 1.2.3 follows OSC 8 links under korTTY's open gesture (user decision D2): only
 * a single, still, primary Cmd/Ctrl+click without Alt navigates a link, and the hover highlight
 * shows only while that gesture is held. korTTY's own OSC 8 click handling relies on this, so a
 * SithTermFX upgrade that changes it fails here.
 */
public class SithTermFxFollowLinkClickTest {

    /** Answers like korTTY's settings provider. */
    private static final SettingsProvider KORTTY = new DefaultSettingsProvider() {
        @Override
        public boolean isFollowLinkGesture(@NotNull ModifierKeys modifiers) {
            return TerminalLinkClickPolicy.isFollowLinkGesture(modifiers);
        }
    };

    @Test
    public void onlyASingleStillPrimaryGestureClickFollowsALink() {
        for (MouseButton button : new MouseButton[] {MouseButton.PRIMARY, MouseButton.SECONDARY, MouseButton.MIDDLE}) {
            for (boolean shortcut : new boolean[] {true, false}) {
                for (boolean alt : new boolean[] {true, false}) {
                    for (int clicks = 0; clicks <= 3; clicks++) {
                        for (boolean still : new boolean[] {true, false}) {
                            boolean follows = TerminalPanel.isFollowLinkClick(KORTTY, click(button, shortcut, alt, clicks, still));
                            assertWithMessage(button + " shortcut=" + shortcut + " alt=" + alt + " clicks=" + clicks
                                + " still=" + still).that(follows)
                                .isEqualTo(button == MouseButton.PRIMARY && shortcut && !alt && clicks <= 1 && still);
                        }
                    }
                }
            }
        }
    }

    @Test
    public void theHoverHighlightShowsOnlyWhileTheGestureIsHeld() {
        HyperlinkStyle link = new HyperlinkStyle(new TextStyle(), new LinkInfo(() -> { }));

        assertWithMessage("no modifier").that(TerminalPanel.isLinkHoverActive(KORTTY, link, ModifierKeys.NONE)).isFalse();
        assertWithMessage("shortcut").that(TerminalPanel.isLinkHoverActive(KORTTY, link,
            ModifierKeys.of(false, false, false, false, true))).isTrue();
        assertWithMessage("AltGr").that(TerminalPanel.isLinkHoverActive(KORTTY, link,
            ModifierKeys.of(false, true, true, false, true))).isFalse();
    }

    private static MouseEvent click(MouseButton button, boolean shortcut, boolean alt, int clicks, boolean still) {
        boolean mac = System.getProperty("os.name", "").toLowerCase().contains("mac");
        return new MouseEvent(MouseEvent.MOUSE_CLICKED, 1, 1, 1, 1, button, clicks, false,
            shortcut && !mac, alt, shortcut && mac, button == MouseButton.PRIMARY, button == MouseButton.MIDDLE,
            button == MouseButton.SECONDARY, false, false, still, null);
    }
}
