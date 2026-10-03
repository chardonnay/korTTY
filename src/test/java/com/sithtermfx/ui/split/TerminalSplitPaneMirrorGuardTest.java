package com.sithtermfx.ui.split;

import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

/**
 * Broadcast mode mirrors a pane's keys only into the other panes that the mirror guard accepts. korTTY
 * uses it to skip a pane that is sending a paced paste, so no mirrored key lands between two of its
 * lines. The target choice is a pure function, checked here; that both broadcast paths use it, and
 * that a failing guard counts as "no", is read from the source (line-ending agnostic), because a
 * live split pane needs a JavaFX toolkit.
 */
public class TerminalSplitPaneMirrorGuardTest {

    private static final Path SOURCE = Path.of("src/main/java/com/sithtermfx/ui/split/TerminalSplitPane.java");

    @Test
    public void everyOtherPaneTheGuardAcceptsGetsTheInputInOrder() {
        List<String> panes = List.of("left", "middle", "right", "bottom");

        assertThat(TerminalSplitPane.mirrorTargets(panes, "middle", pane -> true))
            .containsExactly("left", "right", "bottom").inOrder();
        assertThat(TerminalSplitPane.mirrorTargets(panes, "middle", pane -> !pane.equals("right")))
            .containsExactly("left", "bottom").inOrder();
    }

    @Test
    public void theSourcePaneNeverGetsItsOwnInput() {
        String source = new String("left");
        List<String> panes = List.of(source, "right");

        assertThat(TerminalSplitPane.mirrorTargets(panes, source, pane -> true)).containsExactly("right");
    }

    @Test
    public void aGuardThatRefusesEveryPaneLeavesNoTarget() {
        assertThat(TerminalSplitPane.mirrorTargets(List.of("a", "b"), "a", pane -> false)).isEmpty();
        assertThat(TerminalSplitPane.mirrorTargets(List.<String>of(), null, pane -> true)).isEmpty();
    }

    @Test
    public void bothBroadcastPathsAskTheGuard() throws IOException {
        String source = source();
        String typed = sourceOf(source, "private void broadcastToOthers(@NotNull SithTermFxWidget sourceWidget, @NotNull String data) {");
        String encoded = sourceOf(source, "private void broadcastToOthers(@NotNull SithTermFxWidget sourceWidget,\n"
            + "                                   @NotNull Function<SithTermFxWidget, byte[]> bytesFor) {");

        for (String path : List.of(typed, encoded)) {
            assertThat(path).isNotEmpty();
            assertThat(path).contains("mirrorTargets(getAllWidgets(), sourceWidget, this::acceptsMirroredInput)");
            assertThat(count(path, "getAllWidgets()")).isEqualTo(1);
        }
    }

    @Test
    public void aFailingGuardMeansNoAndNoGuardMeansEveryPane() throws IOException {
        String source = source();
        String accepts = sourceOf(source, "private boolean acceptsMirroredInput(@NotNull SithTermFxWidget widget) {");
        assertThat(accepts).contains("return mirrorTargetGuard.test(widget);");
        assertThat(accepts).contains("catch (RuntimeException e)");
        assertThat(accepts).contains("return false;");

        String setter = sourceOf(source, "public void setMirrorTargetGuard(@Nullable Predicate<SithTermFxWidget> guard) {");
        assertThat(setter).contains("this.mirrorTargetGuard = guard != null ? guard : widget -> true;");
        assertThat(source).contains("private Predicate<SithTermFxWidget> mirrorTargetGuard = widget -> true;");
    }

    private static String source() throws IOException {
        return Files.readString(SOURCE, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    /** The member that starts with {@code signature}, up to the first line closing a member, or "" when absent. */
    private static String sourceOf(String source, String signature) {
        int start = source.indexOf(signature);
        if (start < 0) {
            return "";
        }
        int end = source.indexOf("\n    }\n", start);
        return end < 0 ? source.substring(start) : source.substring(start, end);
    }

    private static int count(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }
}
