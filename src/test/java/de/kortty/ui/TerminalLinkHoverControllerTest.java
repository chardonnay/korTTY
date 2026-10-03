package de.kortty.ui;

import com.sithtermfx.core.compatibility.Point;
import de.kortty.ui.TerminalLinkResolver.Link;
import org.testng.annotations.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.google.common.truth.Truth.assertThat;
import static de.kortty.ui.TerminalLinkClickPolicy.HitKind.AUTO;
import static de.kortty.ui.TerminalLinkClickPolicy.HitKind.OSC8;

/**
 * What the hover over a terminal link shows, and how it is wired into every pane. The decisions are
 * pure; the wiring needs a JavaFX toolkit, so it is pinned in the source (CRLF-safe,
 * whitespace-insensitive) and exercised for real by {@code terminalLinksSmoke}.
 */
public class TerminalLinkHoverControllerTest {

    private static final URI TARGET = URI.create("https://example.com/docs");

    @Test
    public void theTooltipShowsTheShortcutHintAndTheRealTarget() {
        Link osc8 = link(OSC8, TARGET, "docs");

        assertThat(TerminalLinkHoverController.tooltipText(osc8, true))
            .isEqualTo(I18n.get("terminal.links.hint.mac") + "\n" + "https://example.com/docs");
        assertThat(TerminalLinkHoverController.tooltipText(osc8, false))
            .isEqualTo(I18n.get("terminal.links.hint.other") + "\n" + "https://example.com/docs");
        assertThat(I18n.get("terminal.links.hint.mac")).contains("Cmd");
    }

    @Test
    public void theTooltipShowsTheTargetInItsDisplayForm() {
        URI lookAlike = TerminalLinkOpener.allowedBrowseUri("https://exаmple.com/login").orElseThrow();

        String tooltip = TerminalLinkHoverController.tooltipText(link(OSC8, lookAlike, "https://example.com/login"), false);

        assertThat(tooltip).endsWith("\n" + TerminalLinkOpener.displayTarget(lookAlike));
        assertThat(tooltip).contains("https://xn--");
        assertThat(tooltip).doesNotContain("а");
    }

    @Test
    public void aLinkKorttyDoesNotOpenSaysSo() {
        Link refused = link(AUTO, null, "mailto:a@example.com?attach=~/.ssh/id_rsa");

        assertThat(TerminalLinkHoverController.tooltipText(refused, true)).isEqualTo(I18n.get("terminal.links.notAllowed"));
        assertThat(TerminalLinkHoverController.showsHandCursor(refused)).isFalse();
        assertThat(TerminalLinkHoverController.drawsUnderline(refused)).isFalse();
    }

    @Test
    public void aFileShowsTheSnippetEditorHintAndThePathOrTheRealTarget() {
        Link printed = new Link(AUTO, null, "src/App.java:42", new Point(0, 0), new Point(14, 0),
            TerminalFileLink.printed("src/App.java:42"));
        TerminalFileLink target = TerminalFileLink.fromFileUri("file://web01/etc/hosts").orElseThrow();
        Link osc8 = new Link(OSC8, null, "hosts", new Point(0, 0), new Point(4, 0), target);

        assertThat(TerminalLinkHoverController.tooltipText(printed, true))
            .isEqualTo(I18n.get("terminal.links.hint.file.mac") + "\n" + "src/App.java");
        assertThat(TerminalLinkHoverController.tooltipText(osc8, false))
            .isEqualTo(I18n.get("terminal.links.hint.file.other") + "\n" + "file://web01/etc/hosts");
        assertThat(I18n.get("terminal.links.hint.file.mac")).contains("Cmd");
        // A file opens on a click, so it gets the hand cursor and, found in plain text, the underline.
        assertThat(TerminalLinkHoverController.showsHandCursor(printed)).isTrue();
        assertThat(TerminalLinkHoverController.drawsUnderline(printed)).isTrue();
        assertThat(TerminalLinkHoverController.showsHandCursor(osc8)).isTrue();
        assertThat(TerminalLinkHoverController.drawsUnderline(osc8)).isFalse();
        // A file the pane does not open (another host, no file handler) is a link korTTY does not open.
        Link refused = osc8.withoutFile();
        assertThat(TerminalLinkHoverController.tooltipText(refused, true)).isEqualTo(I18n.get("terminal.links.notAllowed"));
        assertThat(TerminalLinkHoverController.showsHandCursor(refused)).isFalse();
    }

    @Test
    public void korttyUnderlinesOnlyPlainTextLinksBecauseSithTermFxUnderlinesOsc8Links() {
        assertThat(TerminalLinkHoverController.drawsUnderline(link(AUTO, TARGET, "https://example.com/docs"))).isTrue();
        assertThat(TerminalLinkHoverController.drawsUnderline(link(OSC8, TARGET, "docs"))).isFalse();
        assertThat(TerminalLinkHoverController.drawsUnderline(null)).isFalse();
    }

    @Test
    public void theHandCursorShowsExactlyWhenAClickCanOpenTheLink() {
        assertThat(TerminalLinkHoverController.showsHandCursor(link(AUTO, TARGET, "https://example.com/docs"))).isTrue();
        assertThat(TerminalLinkHoverController.showsHandCursor(link(OSC8, TARGET, "docs"))).isTrue();
        assertThat(TerminalLinkHoverController.showsHandCursor(link(OSC8, null, "docs"))).isFalse();
        assertThat(TerminalLinkHoverController.showsHandCursor(null)).isFalse();
    }

    @Test
    public void theMismatchQuestionNamesTheShownHostAndTheRealTarget() {
        URI target = URI.create("https://evil.example/login");

        String message = TerminalLinkMismatchDialog.message("example.com", target);

        assertThat(message).isEqualTo(I18n.get("terminal.links.mismatch.body", "example.com", "https://evil.example/login"));
        assertThat(message).contains("example.com");
        assertThat(message).contains("https://evil.example/login");
    }

    @Test
    public void everyPanelInstallsTheHoverAfterSithTermFxsOwnHandlers() throws IOException {
        String widget = source("src/main/java/de/kortty/ui/KorttyTermWidget.java");

        int superCall = widget.indexOf("super(settingsProvider, terminalTextBuffer, styleState);");
        // The pane's live link kinds, shared with the click and the context menu's press filter; a
        // file the pane's file handler does not open is no link target.
        assertThat(widget).contains("TerminalLinkHoverController.LinkFinder linkFinder = "
            + "(buffer, cell) -> openable(TerminalLinkResolver.linkAt(buffer, cell, plainTextLinkKinds.get()));");
        int hover = widget.indexOf("linkHover = TerminalLinkHoverController.install(this, linkFinder, "
            + "() -> linkOverlay.get());");
        assertThat(superCall).isAtLeast(0);
        assertThat(hover).isGreaterThan(superCall);
        assertThat(hover).isLessThan(widget.indexOf("void setPlainTextLinkKinds(", superCall));
        // A mismatch is asked about after the click, and only then opened through the allowlist.
        assertThat(widget).contains("? TerminalLinkOpener.visibleHostMismatch(hit.text(), target)");
        assertThat(widget).contains("Platform.runLater(() -> { "
            + "if (mismatchConfirmation.confirm(getCanvas(), shownHost.get(), target)) { linkOpener.open(target); } });");
    }

    @Test
    public void theCursorIsSetInAHandlerSoItRunsAfterSithTermFxsAndEverythingIsTornDown() throws IOException {
        String hover = code("src/main/java/de/kortty/ui/TerminalLinkHoverController.java");

        assertThat(hover).contains("void followMouseMoves() { "
            + "panel.getCanvas().addEventHandler(MouseEvent.MOUSE_MOVED, this::onMoved); }");
        assertThat(hover.split("MouseEvent.MOUSE_MOVED", -1)).hasLength(2);
        assertThat(hover).doesNotContain("addEventFilter(MouseEvent.MOUSE_MOVED");
        // SithTermFX adds its own MOUSE_MOVED handler in TerminalPanel.init(), after the panel's
        // constructor; the hover's goes after it there, so the cursor korTTY sets wins.
        String widget = code("src/main/java/de/kortty/ui/KorttyTermWidget.java");
        assertThat(widget).contains("public void init() { super.init(); linkHover.followMouseMoves(); }");
        assertThat(hover).contains("panel.getCanvas().setCursor(showsHandCursor(link) ? Cursor.HAND : Cursor.DEFAULT);");
        assertThat(hover).contains("canvas.addEventHandler(MouseEvent.MOUSE_EXITED, event -> controller.clear());");
        assertThat(hover).contains("canvas.addEventFilter(MouseEvent.MOUSE_DRAGGED, event -> controller.clear());");
        assertThat(hover).contains("canvas.addEventFilter(ScrollEvent.SCROLL, event -> controller.clear());");
        assertThat(hover).contains("if (!event.getCode().isModifierKey()) { controller.clear(); }");
        assertThat(hover).contains("canvas.focusedProperty().addListener(");
        assertThat(hover).contains("canvas.sceneProperty().addListener(");
        assertThat(hover).contains("window.focusedProperty().addListener(windowFocus);");
        // The text buffer is followed only while a link is shown, never for the pane's lifetime.
        assertThat(hover.split("addModelListener", -1)).hasLength(2);
        assertThat(hover.indexOf("buffer.addModelListener(textChanged);"))
            .isGreaterThan(hover.indexOf("private void startWatching()"));
        assertThat(hover).contains("watchedBuffer.removeModelListener(textChanged);");
    }

    @Test
    public void theUnderlineGoesIntoTheLinksLayerThroughSceneCoordinates() throws IOException {
        String hover = code("src/main/java/de/kortty/ui/TerminalLinkHoverController.java");
        String splitPane = source("src/main/java/com/sithtermfx/ui/split/TerminalSplitPane.java");

        assertThat(hover).contains("layer.sceneToLocal(canvas.localToScene(segment.startX(), segment.y()))");
        assertThat(hover).contains("line.setMouseTransparent(true);");
        // Each pane hands its widget its own LINKS layer before the terminal view configures it.
        int overlay = splitPane.indexOf("korttyWidget.setLinkOverlay(() -> paneOverlay(widget, PaneOverlayLayer.LINKS));");
        assertThat(overlay).isAtLeast(0);
        assertThat(overlay).isLessThan(splitPane.indexOf("widgetConfigurator.accept(widget);", overlay));
    }

    private static Link link(TerminalLinkClickPolicy.HitKind kind, URI target, String text) {
        return new Link(kind, target, text, new Point(0, 0), new Point(text.length() - 1, 0));
    }

    private static String source(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8).replace("\r\n", "\n").replaceAll("\\s+", " ");
    }

    /** The source without comments and literal contents, whitespace collapsed. */
    private static String code(String path) throws IOException {
        String raw = Files.readString(Path.of(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
        return NoHyperlinkFilterGuardTest.codeOnly(raw).replaceAll("\\s+", " ");
    }
}
