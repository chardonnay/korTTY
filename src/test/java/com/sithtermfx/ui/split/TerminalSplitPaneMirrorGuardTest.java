package com.sithtermfx.ui.split;

import de.kortty.ui.I18n;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;

import static com.google.common.truth.Truth.assertThat;

/**
 * Broadcast mode mirrors a pane's keys only into the other panes that the mirror guard accepts and,
 * for that key, the mirror input rule admits. korTTY uses the guard to skip a pane that is sending a
 * paced paste, so no mirrored key lands between two of its lines, and one an AI agent drives; the
 * rule keeps a password typed at a prompt out of the panes that do not ask for one. The target
 * choice and the count of held panes are pure functions, checked here; that both broadcast paths use
 * them, and that a failing guard or rule counts as "no", is read from the source (line-ending
 * agnostic), because a live split pane needs a JavaFX toolkit.
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
    public void onlyConnectedPanesTheGuardHoldsAreCountedAsLeftOut() {
        List<String> panes = List.of("left", "agent", "closed", "pacing", "right");
        Predicate<String> connected = pane -> !pane.equals("closed");
        Predicate<String> guard = pane -> !pane.equals("agent") && !pane.equals("pacing") && !pane.equals("closed");

        assertThat(TerminalSplitPane.countHeldMirrorTargets(panes, connected, guard)).isEqualTo(2);
        assertThat(TerminalSplitPane.countHeldMirrorTargets(panes, connected, pane -> true)).isEqualTo(0);
        assertThat(TerminalSplitPane.countHeldMirrorTargets(panes, pane -> false, pane -> false)).isEqualTo(0);
        assertThat(TerminalSplitPane.countHeldMirrorTargets(List.<String>of(), connected, guard)).isEqualTo(0);
    }

    @Test
    public void theContextMenuNamesTheLeftOutPanesOnlyWhenThereAreAny() {
        assertThat(TerminalSplitPane.heldMirrorTargetsText(0, 3)).isNull();
        assertThat(TerminalSplitPane.heldMirrorTargetsText(1, 3))
            .isEqualTo(I18n.get(TerminalSplitPane.BROADCAST_HELD_KEY, 1, 3));
        assertThat(TerminalSplitPane.heldMirrorTargetsText(1, 3)).contains("1");
        assertThat(TerminalSplitPane.heldMirrorTargetsText(1, 3)).contains("3");
        assertThat(TerminalSplitPane.heldMirrorTargetsText(1, 3)).doesNotContain(TerminalSplitPane.BROADCAST_HELD_KEY);
    }

    @Test
    public void bothBroadcastPathsSendOnlyToTheReceivers() throws IOException {
        String source = source();
        String typed = sourceOf(source, "private void broadcastToOthers(@NotNull SithTermFxWidget sourceWidget, @NotNull String data) {");
        String encoded = sourceOf(source, "private void broadcastToOthers(@NotNull SithTermFxWidget sourceWidget,\n"
            + "                                   @NotNull Function<SithTermFxWidget, byte[]> bytesFor) {");

        for (String path : List.of(typed, encoded)) {
            assertThat(path).isNotEmpty();
            assertThat(path).contains("for (SithTermFxWidget widget : mirrorReceivers(sourceWidget)) {");
            assertThat(path).doesNotContain("getAllWidgets()");
        }
    }

    @Test
    public void receiversAreConnectedGuardedAndAdmittedByTheRuleAskedOncePerKey() throws IOException {
        String source = source();
        String receivers = sourceOf(source,
            "private @NotNull List<SithTermFxWidget> mirrorReceivers(@NotNull SithTermFxWidget source) {");
        assertThat(receivers).contains(
            "List<SithTermFxWidget> targets = mirrorTargets(getAllWidgets(), source, this::receivesMirroredInput);");
        // No pane to send to: the rule, which may read the panes' screens, is not asked.
        assertThat(receivers.indexOf("if (targets.isEmpty()) {"))
            .isLessThan(receivers.indexOf("mirrorInputRuleFor(source)"));
        assertThat(count(receivers, "mirrorInputRuleFor(source)")).isEqualTo(1);
        assertThat(receivers).contains("return mirrorTargets(targets, source, target -> admitsMirroredInput(admitted, target));");

        String receives = sourceOf(source, "private boolean receivesMirroredInput(@NotNull SithTermFxWidget widget) {");
        assertThat(receives).contains("return isConnected(widget) && acceptsMirroredInput(widget);");
    }

    @Test
    public void aFailingRuleAdmitsNoPaneAndNoRuleAdmitsEveryPane() throws IOException {
        String source = source();
        String ruleFor = sourceOf(source,
            "private @NotNull Predicate<SithTermFxWidget> mirrorInputRuleFor(@NotNull SithTermFxWidget source) {");
        assertThat(ruleFor).contains("return admitted != null ? admitted : widget -> false;");
        assertThat(ruleFor).contains("catch (RuntimeException e)");
        assertThat(ruleFor).contains("return widget -> false;");

        String admits = sourceOf(source, "private static boolean admitsMirroredInput(");
        assertThat(admits).contains("return admitted.test(target);");
        assertThat(admits).contains("return false;");

        String setter = sourceOf(source,
            "public void setMirrorInputRule(@Nullable Function<SithTermFxWidget, Predicate<SithTermFxWidget>> rule) {");
        assertThat(setter).contains("this.mirrorInputRule = rule != null ? rule : source -> widget -> true;");
        assertThat(source).contains(
            "private Function<SithTermFxWidget, Predicate<SithTermFxWidget>> mirrorInputRule = source -> widget -> true;");
    }

    @Test
    public void theContextMenuNoteFollowsBroadcastModeAndTheLiveCount() throws IOException {
        String extras = sourceOf(source(), "private @NotNull Menu createExtrasSubmenu(@NotNull SithTermFxWidget widget) {");
        assertThat(extras).contains(
            "String held = broadcastMode ? heldMirrorTargetsText(countHeldMirrorTargets(), getWidgetCount()) : null;");
        assertThat(extras).contains("heldInfo.setDisable(true);");
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
