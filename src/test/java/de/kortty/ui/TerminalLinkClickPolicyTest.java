package de.kortty.ui;

import com.sithtermfx.ui.settings.ModifierKeys;
import de.kortty.ui.TerminalLinkClickPolicy.Action;
import de.kortty.ui.TerminalLinkClickPolicy.HitKind;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static de.kortty.ui.TerminalLinkClickPolicy.Action.OPEN;
import static de.kortty.ui.TerminalLinkClickPolicy.Action.PASS;
import static de.kortty.ui.TerminalLinkClickPolicy.HitKind.AUTO;
import static de.kortty.ui.TerminalLinkClickPolicy.HitKind.NONE;
import static de.kortty.ui.TerminalLinkClickPolicy.HitKind.OSC8;

/**
 * Pins korTTY's link gate (user decision D2): a link opens only on a single, still Cmd/Ctrl+click,
 * never with AltGr, which Windows reports as Ctrl+Alt.
 *
 * <p>For OSC 8 links SithTermFX 1.2.3 makes the call itself, through the settings provider's
 * {@code isFollowLinkGesture} and its own single-and-still rule ({@code SithTermFxFollowLinkClickTest}
 * pins that), and korTTY's filter leaves those clicks alone. For links korTTY finds in plain text the
 * filter decides alone, with the same rule.
 */
public class TerminalLinkClickPolicyTest {

    @DataProvider
    public Object[][] namedClicks() {
        // description, primary, popupTrigger, shortcut, alt, clickCount, stillSincePress, hit, expected
        return new Object[][] {
            {"Cmd/Ctrl+click on a plain-text link", true, false, true, false, 1, true, AUTO, OPEN},
            {"Cmd/Ctrl+double-click on a plain-text link", true, false, true, false, 2, true, AUTO, PASS},
            {"Cmd/Ctrl+drag released on a plain-text link", true, false, true, false, 1, false, AUTO, PASS},
            {"AltGr (Ctrl+Alt) click on a plain-text link", true, false, true, true, 1, true, AUTO, PASS},
            {"plain click on a plain-text link", true, false, false, false, 1, true, AUTO, PASS},
            {"plain double-click on a plain-text link", true, false, false, false, 2, true, AUTO, PASS},
            {"Cmd/Ctrl+secondary click on a plain-text link", false, false, true, false, 1, true, AUTO, PASS},
            {"Cmd/Ctrl+click that is the popup trigger", true, true, true, false, 1, true, AUTO, PASS},
            // SithTermFX follows OSC 8 links itself, on the gesture korTTY's provider defines.
            {"Cmd/Ctrl+click on an OSC 8 link", true, false, true, false, 1, true, OSC8, PASS},
            {"plain click on an OSC 8 link", true, false, false, false, 1, true, OSC8, PASS},
            {"plain double-click on an OSC 8 link", true, false, false, false, 2, true, OSC8, PASS},
            {"Cmd/Ctrl+click on no link", true, false, true, false, 1, true, NONE, PASS},
            {"plain triple-click on no link", true, false, false, false, 3, true, NONE, PASS},
        };
    }

    @Test(dataProvider = "namedClicks")
    public void namedClick(String description, boolean primary, boolean popupTrigger, boolean shortcut, boolean alt,
                           int clickCount, boolean still, HitKind hit, Action expected) {
        assertWithMessage(description)
            .that(TerminalLinkClickPolicy.decide(primary, popupTrigger, shortcut, alt, clickCount, still, hit))
            .isEqualTo(expected);
    }

    @Test
    public void fullTableOpensOnlyASingleStillGestureClickOnAPlainTextLink() {
        int combinations = 0;
        for (boolean primary : new boolean[] {true, false}) {
            for (boolean popupTrigger : new boolean[] {true, false}) {
                for (boolean shortcut : new boolean[] {true, false}) {
                    for (boolean alt : new boolean[] {true, false}) {
                        for (int clickCount = 0; clickCount <= 4; clickCount++) {
                            for (boolean still : new boolean[] {true, false}) {
                                for (HitKind hit : HitKind.values()) {
                                    combinations++;
                                    Action action = TerminalLinkClickPolicy.decide(
                                        primary, popupTrigger, shortcut, alt, clickCount, still, hit);
                                    String row = "primary=" + primary + " popup=" + popupTrigger
                                        + " shortcut=" + shortcut + " alt=" + alt + " clicks=" + clickCount
                                        + " still=" + still + " hit=" + hit + " -> " + action;
                                    assertWithMessage(row).that(action == OPEN).isEqualTo(primary && !popupTrigger
                                        && hit == AUTO && shortcut && !alt && clickCount == 1 && still);
                                }
                            }
                        }
                    }
                }
            }
        }
        assertThat(combinations).isEqualTo(2 * 2 * 2 * 2 * 5 * 2 * 3);
    }

    @Test
    public void theOpenGestureIsCmdOrCtrlWithoutAlt() {
        assertThat(TerminalLinkClickPolicy.isFollowLinkGesture(ModifierKeys.NONE)).isFalse();
        assertThat(TerminalLinkClickPolicy.isFollowLinkGesture(ModifierKeys.of(false, false, false, false, true))).isTrue();
        assertThat(TerminalLinkClickPolicy.isFollowLinkGesture(ModifierKeys.of(true, false, false, true, true))).isTrue();
        // AltGr on Windows arrives as Ctrl+Alt.
        assertThat(TerminalLinkClickPolicy.isFollowLinkGesture(ModifierKeys.of(false, true, true, false, true))).isFalse();
        assertThat(TerminalLinkClickPolicy.isFollowLinkGesture(ModifierKeys.of(false, false, true, false, false))).isFalse();
        assertThat(TerminalLinkClickPolicy.isFollowLinkGesture(ModifierKeys.of(true, false, false, false, false))).isFalse();
        // macOS Ctrl (not the shortcut there) does not open.
        assertThat(TerminalLinkClickPolicy.isFollowLinkGesture(ModifierKeys.of(false, true, false, false, false))).isFalse();
    }

    @Test
    public void everyPanelInstallsTheClickFilterAndFollowsOnlyTheNotedLink() throws IOException {
        String widget = source("src/main/java/de/kortty/ui/KorttyTermWidget.java").replaceAll("\\s+", " ");

        int panelConstructor = widget.indexOf("super(settingsProvider, terminalTextBuffer, styleState);");
        int install = widget.indexOf("TerminalLinkClickPolicy.install(this, (buffer, cell) -> { "
            + "TerminalLinkResolver.Link link = linkFinder.linkAt(buffer, cell); "
            + "return link != null ? link.hit() : Hit.NONE; }, this::openLink, this::noteOsc8Click);");
        int nextMember = widget.indexOf("void setPlainTextLinkKinds(", panelConstructor);
        assertThat(panelConstructor).isAtLeast(0);
        // The click resolves through the same lookup as the hover and the menu: the pane's live link
        // kinds, and a file only when the pane's file handler opens it.
        assertThat(widget).contains("TerminalLinkHoverController.LinkFinder linkFinder = "
            + "(buffer, cell) -> openable(TerminalLinkResolver.linkAt(buffer, cell, plainTextLinkKinds.get()));");
        assertThat(install).isGreaterThan(panelConstructor);
        assertThat(install).isLessThan(nextMember);
        // Every OSC 8 link the pane shows hands SithTermFX's navigation back to the pane.
        assertThat(widget).contains("setLinkInfoProvider(new KorttyOsc8LinkInfoProvider(panel::fileLinksEnabled, panel::followLink));");
        // The pane opens a navigated link only when the filter noted the same link for that click,
        // and drops the note once SithTermFX's click handler ran.
        assertThat(widget).contains("GestureClick click = gestureClick; gestureClick = null; "
            + "if (click != null && click.linkInfo() == link) { openLink(click.hit()); }");
        assertThat(widget).contains("super.init(); linkHover.followMouseMoves(); ");
        assertThat(widget).contains("getCanvas().addEventHandler(MouseEvent.MOUSE_CLICKED, event -> gestureClick = null);");
        assertThat(widget).contains("private TerminalLinkOpener linkOpener = TerminalLinkOpener.system();");
        // Every opened link goes through the opener's allowlist, after the host-mismatch question,
        // and every file through the pane's file handler, which accepts it once more.
        assertThat(widget).contains("linkOpener.open(target);");
        assertThat(widget).contains("TerminalLinkOpener.visibleHostMismatch(hit.text(), target)");
        assertThat(widget).contains("if (handler == null || !handler.accepts(file)) { return; }");
        assertThat(widget).contains("handler.open(file);");
        assertThat(NoHyperlinkFilterGuardTest.codeOnly(source("src/main/java/de/kortty/ui/KorttyTermWidget.java")))
            .doesNotContain("Desktop");
    }

    @Test
    public void theClickFilterRunsBeforeSithTermFxAndNotesOnlyGestureClicksOnOsc8Links() throws IOException {
        String policy = NoHyperlinkFilterGuardTest.codeOnly(source("src/main/java/de/kortty/ui/TerminalLinkClickPolicy.java"))
            .replaceAll("\\s+", " ");

        assertThat(policy).contains("panel.getCanvas().addEventFilter(MouseEvent.MOUSE_CLICKED,");
        assertThat(policy).doesNotContain("addEventHandler(");
        // korTTY notes only a single, still gesture click itself, independent of SithTermFX's own rule.
        assertThat(policy).contains("if (hit.kind() == HitKind.OSC8) { if (!event.isPopupTrigger() "
            + "&& isFollowLinkGesture(event.isShortcutDown(), event.isAltDown()) "
            + "&& event.getClickCount() == 1 && event.isStillSincePress()) { osc8Clicked.accept(cell, hit); } return; }");
        assertThat(policy).contains("event.consume();");
        assertThat(policy).contains("panel.getCanvas().requestFocus();");
    }

    @Test
    public void korttysSettingsProviderFollowsLinksOnlyOnTheGesture() throws IOException {
        String view = source("src/main/java/de/kortty/ui/TerminalView.java").replaceAll("\\s+", " ");

        assertThat(view).contains("public boolean isFollowLinkGesture(@NotNull com.sithtermfx.ui.settings.ModifierKeys modifiers) "
            + "{ return TerminalLinkClickPolicy.isFollowLinkGesture(modifiers); }");
    }

    private static String source(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
