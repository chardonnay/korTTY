package com.sithtermfx.ui.split;

import de.kortty.ui.I18n;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

/**
 * Broadcast mode mirrors a pane's keys only into the other panes that the mirror guard accepts and,
 * for that key, the mirror input rule admits, and an input mirror (multi-exec) does the same for its
 * members in other tabs and windows. korTTY uses the guard to skip a pane that is sending a paced
 * paste, so no mirrored key lands between two of its lines, and one an AI agent drives; the rule
 * keeps a password typed at a prompt out of the panes that do not ask for one. The target choice is
 * the pure {@link BroadcastTargets}, tested on its own; that both broadcast paths use it, that every
 * target is judged by the guard of the split pane that holds it and encoded by that split pane, and
 * that a failing guard, rule or mirror counts as "no", is read from the source (line-ending agnostic),
 * because a live split pane needs a JavaFX toolkit.
 */
public class TerminalSplitPaneMirrorGuardTest {

    private static final Path SOURCE = Path.of("src/main/java/com/sithtermfx/ui/split/TerminalSplitPane.java");

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
        for (String path : List.of(typedPath(source), encodedPath(source))) {
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
        // BroadcastTargets.admit asks the rule once, and not at all without a pane to send to.
        assertThat(receivers).contains("return BroadcastTargets.admit(mirrorTargetsOf(source), () -> {");
        assertThat(count(receivers, "mirrorInputRuleFor(source)")).isEqualTo(1);
        assertThat(receivers).contains("return target -> admitsMirroredInput(admitted, target);");

        String targets = sourceOf(source,
            "private @NotNull List<SithTermFxWidget> mirrorTargetsOf(@NotNull SithTermFxWidget source) {");
        assertThat(targets).contains("return BroadcastTargets.resolve(source, broadcastMode ? getAllWidgets() : List.of(),\n"
            + "            mirrorMembersBesides(source), TerminalSplitPane::isConnected, this::acceptedByOwner);");
    }

    @Test
    public void onlyAMirroringPaneSendsItsKeysOnBroadcastOrMembership() throws IOException {
        String source = source();
        assertThat(sourceOf(source, "private boolean isMirroring(@NotNull SithTermFxWidget widget) {"))
            .contains("return broadcastMode || isMirrorMember(widget);");

        // The typed characters, the control keys and both broadcast paths ask the same question.
        assertThat(source).contains("widgetPane.addEventFilter(KeyEvent.KEY_TYPED, event -> {\n"
            + "            if (!isMirroring(widget)) return;");
        String route = sourceOf(source,
            "private void routeKeyPressed(@NotNull SithTermFxWidget widget, @NotNull KeyEvent event) {");
        assertThat(route).contains("if (isMirroring(widget)) {\n                String sequence = getControlSequence(event);");
        for (String path : List.of(typedPath(source), encodedPath(source))) {
            assertThat(path).contains("if (!isMirroring(sourceWidget)) return;");
            assertThat(path).doesNotContain("broadcastMode");
        }

        // Members of the mirror reach their other members only while the source is one of them.
        String members = sourceOf(source,
            "private @NotNull List<SithTermFxWidget> mirrorMembersBesides(@NotNull SithTermFxWidget source) {");
        assertThat(members).contains("if (mirror == null || !isMirrorMember(source)) {");
        assertThat(members).contains("List<SithTermFxWidget> members = mirror.otherMembers(source);");
    }

    @Test
    public void aTargetIsJudgedAndEncodedByTheSplitPaneThatHoldsIt() throws IOException {
        String source = source();
        String accepted = sourceOf(source, "private boolean acceptedByOwner(@NotNull SithTermFxWidget widget) {");
        assertThat(accepted).contains("TerminalSplitPane owner = ownerOf(widget);");
        assertThat(accepted).contains("return owner != null && owner.acceptsMirroredInput(widget);");

        String owner = sourceOf(source, "private @Nullable TerminalSplitPane ownerOf(@NotNull SithTermFxWidget widget) {");
        assertThat(owner.indexOf("if (holdsWidget(widget)) {")).isLessThan(owner.indexOf("mirror.ownerOf(widget)"));
        assertThat(owner).contains("return this;");

        String encode = sourceOf(source,
            "public byte @Nullable [] encodeKeyFor(@NotNull SithTermFxWidget target, @NotNull KeyEvent event) {");
        assertThat(encode).contains("return owner != null ? owner.encodeOwnPaneKey(target, event) : null;");
        String route = sourceOf(source,
            "private void routeKeyPressed(@NotNull SithTermFxWidget widget, @NotNull KeyEvent event) {");
        assertThat(route).contains("byte[] bytes = encodeKeyFor(widget, event);");
        assertThat(route).contains("broadcastToOthers(widget, target -> encodeKeyFor(target, event));");
        // Each split pane unwraps its own panes' connectors.
        assertThat(sourceOf(source, "private byte @Nullable [] encodeOwnPaneKey(")).contains(
            "connectorUnwrapper.apply(widget.getTtyConnector())");
    }

    @Test
    public void aFailingMirrorMirrorsNothing() throws IOException {
        String source = source();
        for (String member : List.of(
                sourceOf(source, "private boolean isMirrorMember(@NotNull SithTermFxWidget widget) {"),
                sourceOf(source, "private @NotNull List<SithTermFxWidget> mirrorMembersBesides("),
                sourceOf(source, "private @Nullable TerminalSplitPane ownerOf(@NotNull SithTermFxWidget widget) {"))) {
            assertThat(member).contains("catch (RuntimeException e)");
        }
        assertThat(sourceOf(source, "private boolean isMirrorMember(@NotNull SithTermFxWidget widget) {"))
            .contains("return false;");
        assertThat(source).contains("private @Nullable InputMirror inputMirror;");
        assertThat(sourceOf(source, "public void setInputMirror(@Nullable InputMirror mirror) {"))
            .contains("this.inputMirror = mirror;");
    }

    @Test
    public void aNavigationKeyThatRunsAPaneActionStaysInThatPane() throws IOException {
        String source = source();
        String local = sourceOf(source, "private static boolean performsLocalScrollAction(");
        assertThat(local).contains(
            "if (BroadcastTargets.routeOf(alternateScreen, paneAction) != BroadcastTargets.KeyRoute.LOCAL_ACTION) {");
        String route = sourceOf(source,
            "private void routeKeyPressed(@NotNull SithTermFxWidget widget, @NotNull KeyEvent event) {");
        assertThat(route.indexOf("performsLocalScrollAction(widget, event)"))
            .isLessThan(route.indexOf("broadcastToOthers(widget, target -> encodeKeyFor(target, event));"));
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
        // The tab's own panes, each once, connected and held by this tab's guard.
        assertThat(sourceOf(source(), "public int countHeldMirrorTargets() {")).contains(
            "return BroadcastTargets.countHeld(getAllWidgets(), TerminalSplitPane::isConnected, this::acceptsMirroredInput);");
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

    private static String typedPath(String source) {
        return sourceOf(source, "private void broadcastToOthers(@NotNull SithTermFxWidget sourceWidget, @NotNull String data) {");
    }

    private static String encodedPath(String source) {
        return sourceOf(source, "private void broadcastToOthers(@NotNull SithTermFxWidget sourceWidget,\n"
            + "                                   @NotNull Function<SithTermFxWidget, byte[]> bytesFor) {");
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
