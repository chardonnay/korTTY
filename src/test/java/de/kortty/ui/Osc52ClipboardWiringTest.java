package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.annotations.Test;

/**
 * A program's clipboard write ({@code OSC 52}) on its way from the emulator thread to the clipboard:
 * {@link ShellIntegrationController} keeps the newest write of a pane in its slot, the view takes it
 * on the FX thread for the tab, and {@link TerminalClipboardWriter} writes it through
 * {@code KorttyClipboard} only with the setting on. The widget, the view, the tab and the dialog need
 * a JavaFX stage, so their wiring is pinned against the source; the decisions themselves are tested in
 * {@code Osc52SupportTest} and {@code Osc52ClipboardFlowTest}.
 */
class Osc52ClipboardWiringTest {

    @Test
    void aWriteTravelsFromTheOutputToTheTabOneAtATime() throws IOException {
        String controller = source("ShellIntegrationController.java");
        String onEvent = body(controller, "void onEvent(@NotNull SithTermFxWidget widget, @NotNull ShellIntegrationEvent event) {");
        int write = onEvent.indexOf("if (event instanceof ShellIntegrationEvent.ClipboardWrite write) {");
        assertWithMessage("a clipboard write needs no shell integration, so it comes before the marks' setting check")
            .that(write).isAtLeast(0);
        assertThat(write).isLessThan(onEvent.indexOf("if (!PaneCommandMarks.isMark(event) || !isEnabled()) {"));
        int tooLarge = onEvent.indexOf(
            "if (event instanceof ShellIntegrationEvent.Oversize oversize && oversize.kind() == OwnedOsc.CLIPBOARD) {");
        assertWithMessage("a write too long to keep is still a write, so the status bar can say it was refused")
            .that(tooLarge).isAtLeast(0);
        assertThat(onEvent.substring(tooLarge)).contains("offerClipboardWrite(widget, ShellIntegrationEvent.ClipboardWrite.overCap());");
        assertThat(tooLarge).isLessThan(onEvent.indexOf("if (!PaneCommandMarks.isMark(event) || !isEnabled()) {"));
        String offer = body(controller,
            "private void offerClipboardWrite(SithTermFxWidget widget, ShellIntegrationEvent.ClipboardWrite write) {");
        assertWithMessage("only the first write of a burst schedules the FX thread; later ones replace it")
            .that(offer).contains("if (slot == null || listener == null || !slot.offer(write)) {");
        assertWithMessage("a failing listener frees the slot and never stops the emulator thread")
            .that(offer).contains("} catch (RuntimeException e) {\n            slot.take();");
        assertThat(body(controller, "void attach(@NotNull SithTermFxWidget widget) {"))
            .contains("clipboardWrites.putIfAbsent(widget, new ClipboardWriteSlot());");
        assertThat(body(controller, "void detach(@Nullable SithTermFxWidget widget) {"))
            .contains("clipboardWrites.remove(widget);");

        String view = source("TerminalView.java");
        assertThat(view).contains(
            "shellIntegration.setClipboardWriteListener(widget -> Platform.runLater(() -> onPaneClipboardWrite(widget)));");
        String onWrite = body(view, "private void onPaneClipboardWrite(SithTermFxWidget widget) {");
        assertWithMessage("taken first, so the pane's next write can come even when this one is ignored")
            .that(onWrite.indexOf("shellIntegration.takeClipboardWrite(widget);"))
            .isLessThan(onWrite.indexOf("if (write == null || listener == null || !getOrderedWidgets().contains(widget)) {"));
        assertWithMessage("a closed tab never changes the clipboard")
            .that(body(view, "public void cleanup() {")).contains("clipboardWriteListener = null;");

        assertThat(source("TerminalTab.java")).contains(
            "(widget, write) -> TerminalClipboardWriter.shared().onClipboardWrite(this, write));");
    }

    @Test
    void theWriterNeedsTheSettingAndGoesThroughKorttysClipboard() throws IOException {
        String writer = source("TerminalClipboardWriter.java");
        assertWithMessage("the internal clipboard mode of the enterprise policy applies")
            .that(writer).contains("KorttyClipboard::setText");
        String onWrite = body(writer, "public void onClipboardWrite(TerminalTab tab, ClipboardWrite write) {");
        assertThat(onWrite).contains(
            "write(write, current != null && current.isOsc52ClipboardWriteEnabled(), clipboard);");
        assertThat(onWrite).contains("statusText(outcome, tab.getEffectiveTitle(), I18n::get);");
        assertWithMessage("the log records the size of a write, never its text")
            .that(onWrite).doesNotContain("text()");
        String write = body(writer, "static Outcome write(ClipboardWrite write, boolean allowed, Consumer<String> clipboard) {");
        assertWithMessage("with the setting off nothing is even decoded")
            .that(write.indexOf("if (!allowed) {")).isLessThan(write.indexOf("Osc52Support.decode("));
    }

    @Test
    void theSettingIsShownOffSavedAndReported() throws IOException {
        String dialog = source("SettingsDialog.java");
        assertWithMessage("off unless the settings say otherwise")
            .that(dialog).contains(
                "osc52ClipboardWriteCheck.setSelected(globalSettings != null && globalSettings.isOsc52ClipboardWriteEnabled());");
        assertThat(dialog).contains("terminalGrid.add(osc52ClipboardWriteCheck, 0, terminalRow++, 2, 1);");
        assertThat(dialog).contains("globalSettings.setOsc52ClipboardWriteEnabled(osc52ClipboardWriteCheck.isSelected());");
        assertThat(dialog).contains(
            "tracked.add(new TrackedSetting(\"terminal\", \"osc52_clipboard_write\", gs::isOsc52ClipboardWriteEnabled, true));");
    }

    private static String source(String file) throws IOException {
        // Windows CI checks the sources out with CRLF line endings.
        return Files.readString(Path.of("src/main/java/de/kortty/ui", file), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    /** The text of the method whose declaration is {@code signature}, up to its closing brace at that indent. */
    private static String body(String source, String signature) {
        int start = source.indexOf(signature);
        assertWithMessage("missing " + signature).that(start).isAtLeast(0);
        int lineStart = source.lastIndexOf('\n', start) + 1;
        String indent = source.substring(lineStart, start);
        int end = source.indexOf("\n" + indent + "}\n", start);
        assertWithMessage("no end of " + signature).that(end).isGreaterThan(start);
        return source.substring(start, end);
    }
}
